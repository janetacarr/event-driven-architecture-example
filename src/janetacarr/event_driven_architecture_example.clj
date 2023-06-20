(ns janetacarr.event-driven-architecture-example
  (:require [clojure.core.async :as a]
            [environ.core :refer [env]]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.coercion.malli :as rcm]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [muuntaja.core :as m]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.accept :refer [wrap-accept]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.defaults :refer :all]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.oauth2 :refer [wrap-oauth2]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.reload :refer [wrap-reload]]
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
    (println "Received payment" event)))

(defn user-unsubscribed-consumer
  [events-chan]
  (a/go-loop [event (a/<! events-chan)]
    ;; Do something with event
    (println "User unsubscribed" event)))

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

(def event-bus (a/chan))
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
    (let [{:keys [body-params]} req]
      (if (valid-event? body-params (env :shared-secret))
        (publish-fn (update body-params :event-name keyword))
        (bad-request)))))

(def app
  (ring/ring-handler
   (ring/router
    [""
     ["/webhook" {:post {:no-doc true
                         :summary ""
                         :handler (->event-handler publish-event)
                         :responses {200 [:any]
                                     400 [:any]
                                     500 [:any]}
                         :parameters {:body [:map
                                             [:event-type string?]]}}}]]
    {:data {:muuntaja m/instance
            :coercion rcm/coercion
            :middleware [muuntaja/format-middleware
                         rrc/coerce-exceptions-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware]}})
   (ring/routes
    (ring/redirect-trailing-slash-handler {:method :strip})
    (ring/create-default-handler))
   {:middleware [[parameters/parameters-middleware]]}))

(defn -main
  [& args]
  (reset! server (run-jetty app {:port 8080 :join? false})))
