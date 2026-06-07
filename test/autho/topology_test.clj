(ns autho.topology-test
  (:require [clojure.test :refer :all]
            [autho.topology :as topology]
            [jsonista.core :as json]))

(deftest init-parses-enabled-planes-env-test
  (let [enabled-var #'autho.topology/active-planes
        original @(var-get enabled-var)]
    (try
      (with-redefs [autho.topology/env (fn [key]
                                         (case key
                                           "AUTHO_ENABLED_PLANES" "control, evidence"
                                           nil))]
        (topology/init!)
        (is (= #{:control :evidence} (topology/enabled-planes)))
        (is (= [:control :evidence]
               (get (topology/current-config) :enabledPlanes))))
      (finally
        (reset! (var-get enabled-var) original)))))

(deftest call-with-plane-blocks-disabled-plane-test
  (let [enabled-var #'autho.topology/active-planes
        original @(var-get enabled-var)]
    (try
      (topology/set-enabled-planes! #{:data})
      (let [called? (atom false)
            response (topology/call-with-plane :control
                       (fn []
                         (reset! called? true)
                         {:status 200 :body "ok"}))
            body (json/read-value (:body response) json/keyword-keys-object-mapper)]
        (is (false? @called?))
        (is (= 503 (:status response)))
        (is (= "PLANE_DISABLED" (get-in body [:error :code])))
        (is (= "The control plane is disabled in this deployment."
               (get-in body [:error :message]))))
      (finally
        (reset! (var-get enabled-var) original)))))
