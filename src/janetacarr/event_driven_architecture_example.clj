(ns janetacarr.event-driven-architecture-example
  (:require [clojure.core.async :as a]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [environ.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [bad-request]])
  (:gen-class))

(defn valid-event?
  "Pretend to validate your events here!"
  [event secret]
  event)

(defonce server (atom nil))

;; Consumers only care about getting a channel
;; as an argument. This is our consumer 'interface'
(defn payment-received-consumer
  [events-chan]
  (a/go-loop [event (a/<! events-chan)]
    ;; Do something with event
    (log/info "Received payment" event)
    (recur (a/<! events-chan))))

(defn user-unsubscribed-consumer
  [events-chan]
  (a/go-loop [event (a/<! events-chan)]
    ;; Do something with event
    (log/info "User unsubscribed" event)
    (recur (a/<! events-chan))))

(defn ->event-deserializer
  [publish-fn]
  (fn event-deserializer
    [event-chan]
    (a/go-loop [event (a/<! event-chan)]
      (try
        (let [parsed-event (json/parse-string event true)]
          (if (valid-event? parsed-event (:shared-secret env))
            (publish-fn parsed-event)
            (log/warn "Dropping unsigned event: " event)))
        (catch Exception e
          (log/error "Internal error processing event: "
                     event
                     (.getMessage e))))
      (recur (a/<! event-chan)))))

;; This function will as an adapter for converting
;; from event bus events to consumer events.
;; For now, this will be fairly trivial, but
;; leaves the door open to replacing our event
;; bus with something like Kafka or AWS Kinesis.
;; This also gives us the potential for strangling
;; out our consumers into their own processes/services
(defn setup-consumer
  [consumer-fn publication topic]
  (let [consumer-chan (a/chan)]
    (a/sub publication topic consumer-chan)
    (consumer-fn consumer-chan)))

(def event-bus (a/chan 2048))
(def publication (a/pub event-bus :event-type))
(def payments-consumer (setup-consumer payment-received-consumer
                                       publication
                                       :payment))
(def user-consumer (setup-consumer user-unsubscribed-consumer
                                   publication
                                   :unsubscribed))

(defn publish-event
  [event]
  (a/go (a/>! event-bus event)))

(def serializing-consumer-producer (setup-consumer (->event-deserializer publish-event)
                                                   publication
                                                   nil))

;; If we were to switch out the event-bus for
;; something more cloudy, like Kafka or AWS Kinesis,
;; chances are we'd have to use a different API
;; for publish events. So best to decouple them here.
;; Our HTTP Event handler only cares about HTTP and
;; possibly serializing to something our consumers
;; are equipped to understand, like EDN.
(defn ->event-handler
  [publish-fn]
  (fn [req]
    (let [{:keys [body]} req]
      (if body
        (do (publish-fn (slurp body))
            {:status 202})
        (bad-request)))))

;; Use compojure for brevity
(defroutes app
  (POST "/webhook" [req] (->event-handler publish-event)))

(defn -main
  [& args]
  (reset! server (run-jetty app {:port 8080 :join? false})))
