(ns autho.relation-provider-test
  (:require [clojure.test :refer :all]
            [autho.rebac :as rebac]
            [autho.relation-provider :as provider]
            [autho.jsonrule :as jsonrule]))

(use-fixtures :each
  (fn [f]
    (rebac/clear-relations!)
    (provider/reset-default-provider!)
    (provider/clear-pip-resolvers!)
    (f)
    (provider/clear-pip-resolvers!)
    (provider/reset-default-provider!)
    (rebac/clear-relations!)))

(deftest local-projection-provider-preserves-rebac-result
  (let [alice {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}]
    (is (= :denied (:status (provider/check-relation {} alice "viewer" document))))
    (rebac/add-relation! alice "viewer" document)
    (let [result (provider/check-relation {} alice "viewer" document)]
      (is (= :allowed (:status result)))
      (is (= :projection (:source result)))
      (is (provider/allowed? result)))))

(deftest unknown-and-error-results-do-not-authorize
  (is (false? (provider/allowed? {:status :unknown})))
  (is (false? (provider/allowed? {:status :error}))))

(deftest pip-provider-resolves-a-named-source
  (provider/register-pip-resolver!
   "iam"
   (fn [_request subject relation resource _options]
     (and (= "alice" (:id subject))
          (= "member" relation)
          (= "finance" (:id resource)))))
  (let [result (provider/check-relation {}
                                        {:class "Person" :id "alice"}
                                        "member"
                                        {:class "Group" :id "finance"}
                                        {:source "pip" :pip "iam"})]
    (is (= :allowed (:status result)))
    (is (= :pip (:source result)))
    (is (= "iam" (:pip result)))))

(deftest unconfigured-pip-is-unknown-and-does-not-authorize
  (let [result (provider/check-relation {}
                                        {:class "Person" :id "alice"}
                                        "member"
                                        {:class "Group" :id "finance"}
                                        {:source :pip :pip "missing"})]
    (is (= :unknown (:status result)))
    (is (false? (provider/allowed? result)))))

(deftest hybrid-provider-confirms-projection-with-pip
  (let [alice {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}]
    (rebac/add-relation! alice "viewer" document)
    (provider/register-pip-resolver! "documents" (fn [& _] true))
    (let [result (provider/check-relation {} alice "viewer" document
                                          {:source :hybrid :pip "documents"})]
      (is (= :allowed (:status result)))
      (is (= :hybrid (:source result)))
      (is (= :allowed (get-in result [:projection :status])))
      (is (= :allowed (get-in result [:pipResult :status]))))))

(deftest policy-relation-clause-selects-its-pip-provider
  (provider/register-pip-resolver! "iam" (fn [& _] {:status :allowed :version 48}))
  (is (= {:value true}
         (jsonrule/evaluateRule
          {:conditions [["relation" "$s" "member" "$r"
                         {:source "pip" :pip "iam"}]]}
          {:subject {:class "Person" :id "alice"}
           :resource {:class "Group" :id "finance"}}))))

(deftest configured-rest-pip-posts-a-generic-relation-check
  (let [request {:context {:purpose "approval"} :tenantId "tenant-a"}
        subject {:class "Person" :id "alice"}
        resource {:class "Group" :id "finance"}
        captured (atom nil)]
    (with-redefs [clj-http.client/post (fn [url options]
                                         (reset! captured {:url url :options options})
                                         {:status 200 :body {:status "allowed" :version 48}})]
      (provider/configure-pip-resolvers!
       [{:name "iam" :type :rest :url "https://iam.example/check" :timeout-ms 250}])
      (let [result (provider/check-relation request subject "member" resource
                                            {:source :pip :pip "iam"})]
        (is (= :allowed (:status result)))
        (is (= :pip (:source result)))
        (is (= "https://iam.example/check" (:url @captured)))
        (is (= subject (get-in @captured [:options :form-params :subject])))
        (is (= "tenant-a" (get-in @captured [:options :form-params :tenantId])))
        (is (= 250 (get-in @captured [:options :socket-timeout])))))))

(deftest configured-rest-pip-does-not-authorize-on-failure
  (with-redefs [clj-http.client/post (fn [& _] {:status 503 :body {}})]
    (provider/configure-pip-resolvers!
     [{:name "iam" :type :rest :url "https://iam.example/check"}])
    (let [result (provider/check-relation {} {:id "alice"} "member" {:id "finance"}
                                          {:source :pip :pip "iam"})]
      (is (= :error (:status result)))
      (is (false? (provider/allowed? result))))))

(deftest local-projection-is-isolated-by-tenant
  (let [alice {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}]
    (rebac/with-tenant "tenant-a"
      (rebac/add-relation! alice "viewer" document))
    (is (= :allowed
           (:status (provider/check-relation {:tenantId "tenant-a"}
                                             alice "viewer" document))))
    (is (= :denied
           (:status (provider/check-relation {:tenantId "tenant-b"}
                                             alice "viewer" document))))
    (is (= 1 (count (rebac/with-tenant "tenant-a" (rebac/list-relations)))))
    (is (empty? (rebac/with-tenant "tenant-b" (rebac/list-relations))))))

(deftest bounded-staleness-does-not-authorize-an-old-projection
  (let [alice {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}]
    (rebac/with-tenant "tenant-a"
      (rebac/add-relation! alice "viewer" document
                           {:source "documents" :receivedAt "2020-01-01T00:00:00Z"}))
    (let [result (provider/check-relation {:tenantId "tenant-a"} alice "viewer" document
                                          {:consistency "bounded-staleness" :maxStalenessMs 1000})]
      (is (= :unknown (:status result)))
      (is (false? (provider/allowed? result))))))

(deftest fresh-consistency-confirms-the-projection-with-a-pip
  (let [alice {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}]
    (rebac/add-relation! alice "viewer" document)
    (provider/register-pip-resolver! "documents" (fn [& _] true))
    (is (= :allowed (:status (provider/check-relation {} alice "viewer" document
                                                      {:consistency :fresh :pip "documents"}))))
    (is (= :unknown (:status (provider/check-relation {} alice "viewer" document
                                                      {:consistency :fresh}))))))

(deftest fail-closed-requires-projection-freshness-metadata
  (let [alice {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}]
    (rebac/add-relation! alice "viewer" document)
    (is (= :unknown (:status (provider/check-relation {} alice "viewer" document
                                                      {:consistency :fail-closed}))))))
