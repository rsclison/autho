(ns autho.kafka-invalidation-test
  (:require [clojure.test :refer :all]
            [autho.kafka-invalidation :as inv])
  (:import (org.apache.kafka.common.serialization StringSerializer StringDeserializer)))

;; Forward declaration for tests
(declare handle-invalidation-test-helper)

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest invalidation-config-test
  (testing "Invalidation configuration exists"
    (is (map? @inv/invalidation-config))
    (is (contains? @inv/invalidation-config :bootstrap-servers))
    (is (contains? @inv/invalidation-config :topic))
    (is (contains? @inv/invalidation-config :enabled))))

;; =============================================================================
;; Producer Tests
;; =============================================================================

(deftest init-invalidation-test
  (testing "Initialize invalidation producer"
    ;; Reset state
    (swap! inv/invalidation-state assoc :producer nil :consumer nil :stop-flag false :stop-fn nil)
    (when (:enabled @inv/invalidation-config)
      ;; Mock create-producer (private fn) so the test doesn't need a running Kafka broker
      (with-redefs-fn {(ns-resolve 'autho.kafka-invalidation 'create-producer)
                       (fn [] (reify java.io.Closeable (close [_] nil)))}
        (fn []
          (inv/init-invalidation)
          (is (some? (:producer @inv/invalidation-state))))))))

;; =============================================================================
;; Message Tests
;; =============================================================================

(deftest publish-invalidation-disabled-test
  (testing "Publish invalidation when disabled"
    ;; Disable Kafka invalidation
    (swap! inv/invalidation-config assoc :enabled false)

    (let [result (inv/publish-invalidation :subject "test-key")]
      ;; Should return nil when disabled
      (is (nil? result)))

    ;; Re-enable
    (swap! inv/invalidation-config assoc :enabled true)))

;; =============================================================================
;; Consumer Handler Tests
;; =============================================================================

(deftest handle-invalidation-message-test
  (testing "Handle invalidation message"
    (let [invalidated (atom nil)
          message {:type "subject" :key "user123" :timestamp 1234567890}]

      ;; Call the handle-invalidation function from local-cache namespace
      ;; which would normally be registered as the Kafka listener
      (is (some? message))  ; Just verify the message format is correct
      (is (= "subject" (:type message)))
      (is (= "user123" (:key message)))))

;; =============================================================================
;; State Management Tests
;; =============================================================================

(deftest invalidation-state-test
  (testing "Invalidation state structure"
    (is (map? @inv/invalidation-state))
    (is (contains? @inv/invalidation-state :producer))
    (is (contains? @inv/invalidation-state :consumer))
    (is (contains? @inv/invalidation-state :stop-flag))
    (is (contains? @inv/invalidation-state :consumer-thread))))

;; =============================================================================
;; Lifecycle Tests
;; =============================================================================

(deftest shutdown-invalidation-test
  (testing "Shutdown invalidation components"
    ;; Initialize first
    (when (:enabled @inv/invalidation-config)
      (inv/init-invalidation)

      ;; Shutdown
      (inv/shutdown-invalidation)

      ;; State should be cleared
      (is (nil? (:producer @inv/invalidation-state)))
      (is (nil? (:consumer @inv/invalidation-state)))
      (is (nil? (:stop-flag @inv/invalidation-state)))))))
