(ns autho.features-test
  (:require [clojure.test :refer :all]
            [autho.features :as features]))

(deftest demo-enterprise-license-enables-enterprise-features
  (let [active-features-var #'autho.features/active-features
        active-claims-var   #'autho.features/active-claims
        active-features     (var-get active-features-var)
        active-claims       (var-get active-claims-var)
        original-features   @active-features
        original-claims     @active-claims]
    (try
      (reset! active-features #{:is-authorized :who-authorized :what-authorized})
      (reset! active-claims nil)
      (with-redefs-fn {#'autho.features/env
                       (fn [key]
                         (case key
                           "AUTHO_LICENSE_KEY" nil
                           "AUTHO_DEMO_LICENSE_TIER" "enterprise"
                           "AUTHO_DEMO_LICENSE" nil))}
        (fn []
          (features/init!)
          (is (= :enterprise (features/active-tier)))
          (is (features/licensed? :audit))
          (is (features/licensed? :simulate))
          (is (features/licensed? :shadow))
          (is (features/licensed? :kafka-pip))
          (is (features/licensed? :multi-instance))
          (is (= true (:demo (features/licence-info))))))
      (finally
        (reset! active-features original-features)
        (reset! active-claims original-claims)))))

(deftest entitlements-expose-signed-decision-and-instance-limits-test
  (let [active-features-var #'autho.features/active-features
        active-claims-var #'autho.features/active-claims
        active-features (var-get active-features-var)
        active-claims (var-get active-claims-var)
        original-features @active-features
        original-claims @active-claims]
    (try
      (reset! active-features #{:is-authorized})
      (reset! active-claims {:tier "pro" :decisions 10000 :instances 3})
      (is (= 10000 (features/decision-limit)))
      (is (= {:tier "pro" :monthlyDecisionLimit 10000 :instanceLimit 3}
             (features/entitlements)))
      (finally
        (reset! active-features original-features)
        (reset! active-claims original-claims)))))

(deftest quota-enforcement-mode-defaults-to-observation-test
  (with-redefs-fn {#'autho.features/env (constantly nil)}
    #(is (= :observation (features/quota-enforcement-mode)))))

(deftest quota-enforcement-mode-accepts-only-known-values-test
  (with-redefs-fn {#'autho.features/env (fn [_] "hard")}
    #(is (= :hard (features/quota-enforcement-mode))))
  (with-redefs-fn {#'autho.features/env (fn [_] "unknown")}
    #(is (= :observation (features/quota-enforcement-mode)))))
