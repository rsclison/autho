(ns autho.circuit-breaker
  "Per-endpoint circuit breakers for external PIP calls.
   Uses diehard/Failsafe under the hood.
   Behaviour: fail-closed (returns nil when circuit is open)."
  (:require [diehard.circuit-breaker :as dcb]
            [diehard.core :as dh]
            [com.brunobonacci.mulog :as u])
  (:import (org.slf4j LoggerFactory)))

(defonce ^:private logger (LoggerFactory/getLogger "autho.circuit-breaker"))

;; Map from endpoint key (string URL or PIP identifier) -> circuit breaker
(defonce ^:private breakers (atom {}))

(defn- make-breaker [key]
  (dcb/circuit-breaker
   {:failure-threshold-ratio [3 10]    ; opens after 3 failures in 10 executions
    :delay-ms 10000                     ; wait 10s before attempting half-open
    :on-open      (fn [_]
                    (u/log ::circuit-open :endpoint key)
                    (.warn logger "Circuit breaker OPEN for {}" key))
    :on-close     (fn [_]
                    (u/log ::circuit-closed :endpoint key)
                    (.info logger "Circuit breaker CLOSED for {}" key))
    :on-half-open (fn [_]
                    (u/log ::circuit-half-open :endpoint key)
                    (.info logger "Circuit breaker HALF-OPEN for {}" key))}))

(defn call
  "Calls f with the circuit breaker for key (e.g. a URL or PIP class name).
   Returns nil when the circuit is open (fail-closed: attribute treated as missing)."
  [key f]
  (let [cb (or (get @breakers key)
               (let [new-cb (make-breaker key)]
                 (swap! breakers assoc key new-cb)
                 new-cb))]
    (try
      (dh/with-circuit-breaker cb (f))
      (catch Exception e
        (let [cause (.getCause e)
              open? (or (instance? dev.failsafe.CircuitBreakerOpenException e)
                        (instance? dev.failsafe.CircuitBreakerOpenException cause))]
          (if open?
            (do
              (u/log ::call-rejected :endpoint key)
              (.warn logger "Call rejected — circuit OPEN for {}" key)
              nil)
            (throw e)))))))

(defn reset-all!
  "Reset all circuit breakers (useful for testing or after maintenance)."
  []
  (reset! breakers {})
  (.info logger "All circuit breakers reset"))
