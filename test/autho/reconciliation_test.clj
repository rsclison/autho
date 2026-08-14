(ns autho.reconciliation-test
  (:require [clojure.test :refer :all]
            [autho.rebac :as rebac]
            [autho.reconciliation :as sut]
            [clj-http.client :as http]))

(deftest rest-source-is-read-only-and-produces-a-report
  (reset! sut/sources {"iam" {:name "iam" :type :rest :url "https://iam.example/snapshot"}})
  (with-redefs [http/get (fn [_ _] {:status 200 :body {:tuples [{:subject {:class "Person" :id "alice"} :relation "member" :resource {:class "Group" :id "finance"}}]}})]
    (let [report (rebac/with-tenant "tenant-a" (sut/reconcile-source! "iam"))]
      (is (= "iam" (:source report)))
      (is (= 1 (:expectedCount report))))))

(deftest scheduled-source-is-scoped-to-its-declared-tenants
  (reset! sut/sources {"iam" {:name "iam" :type :rest :url "https://iam.example/snapshot"
                              :interval-ms 60000 :tenant-ids ["tenant-a" "tenant-b"]}})
  (let [reports (with-redefs [sut/reconcile-source!
                              (fn [source] {:source source :tenantId rebac/*tenant-id*})]
                  (sut/run-scheduled-source! "iam"))]
    (is (= ["tenant-a" "tenant-b"] (mapv :tenantId reports)))
    (is (= ["iam" "iam"] (mapv :source reports)))))
