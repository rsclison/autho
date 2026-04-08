(ns autho.policy-format-test
  (:require [clojure.test :refer :all]
            [autho.policy-format :as policy-format]))

(deftest normalize-strategy-converts-supported-strings-to-keywords-test
  (is (= :almost_one_allow_no_deny (policy-format/normalize-strategy "almost_one_allow_no_deny")))
  (is (= :deny-unless-permit (policy-format/normalize-strategy "deny-unless-permit")))
  (is (= :permit-unless-deny (policy-format/normalize-strategy "permit-unless-deny"))))

(deftest normalize-policy-updates-strategy-field-test
  (is (= {:resourceClass "Facture"
          :strategy :almost_one_allow_no_deny
          :rules []}
         (policy-format/normalize-policy {:resourceClass "Facture"
                                          :strategy "almost_one_allow_no_deny"
                                          :rules []}))))
