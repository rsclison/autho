(ns autho.usage-test
  (:require [clojure.test :refer :all]
            [autho.usage :as usage]))

(use-fixtures
  :each
  (fn [test-fn]
    (usage/init!)
    (usage/clear-usage!)
    (try
      (test-fn)
      (finally
        (usage/clear-usage!)))))

(def acme-production
  {:tenantId "acme"
   :organizationId "org-acme"
   :projectId "payments"
   :environment "production"})

(deftest records-and-returns-monthly-decision-usage-test
  (dotimes [_ 3] (usage/record-decision! acme-production))
  (let [result (usage/usage acme-production)]
    (is (= (usage/current-month) (:month result)))
    (is (= "acme" (:tenantId result)))
    (is (= "org-acme" (:organizationId result)))
    (is (= "payments" (:projectId result)))
    (is (= "production" (:environment result)))
    (is (= 3 (:decisionCount result)))
    (is (some? (:updatedAt result)))))

(deftest usage-is-isolated-by-product-scope-test
  (usage/record-decision! acme-production)
  (usage/record-decision! (assoc acme-production :environment "staging"))
  (usage/record-decision! (assoc acme-production :environment "staging"))
  (is (= 1 (:decisionCount (usage/usage acme-production))))
  (is (= 2 (:decisionCount (usage/usage (assoc acme-production :environment "staging"))))))

(deftest absent-scope-is-not-an-implicit-cross-scope-aggregate-test
  (usage/record-decision! acme-production)
  (is (= 0 (:decisionCount (usage/usage {:tenantId "acme"}))))
  (is (= 1 (:decisionCount (usage/usage acme-production)))))

(deftest quota-status-uses-deployment-wide-usage-test
  (dotimes [_ 3] (usage/record-decision! acme-production))
  (dotimes [_ 5] (usage/record-decision! (assoc acme-production :environment "staging")))
  (let [quota (usage/quota-status acme-production 10)]
    (is (= "observation" (:enforcement quota)))
    (is (= "warning" (:status quota)))
    (is (= 10 (:monthlyDecisionLimit quota)))
    (is (= 8 (:deploymentDecisionCount quota)))
    (is (= 3 (:scopeDecisionCount quota)))
    (is (= 2 (:remaining quota)))
    (is (= 80 (:percentUsed quota)))))

(deftest quota-status-reports-exceeded-without-blocking-test
  (dotimes [_ 2] (usage/record-decision! acme-production))
  (let [quota (usage/quota-status acme-production 1)]
    (is (= "exceeded" (:status quota)))
    (is (= 0 (:remaining quota)))
    (is (= 200 (:percentUsed quota)))))

(deftest usage-rejects-invalid-month-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"YYYY-MM"
                        (usage/usage acme-production "August-2026"))))

(deftest reserve-decision-enforces-the-monthly-limit-atomically-test
  (is (true? (usage/reserve-decision! acme-production 2)))
  (is (true? (usage/reserve-decision! acme-production 2)))
  (is (false? (usage/reserve-decision! acme-production 2)))
  (is (= 2 (:decisionCount (usage/total-usage))))
  (is (= 2 (:decisionCount (usage/usage acme-production)))))
