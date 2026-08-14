(ns autho.rebac-test
  (:require [clojure.test :refer :all]
            [autho.rebac :as rebac]
            [clojure.java.jdbc :as jdbc]))

(use-fixtures :each
  (fn [f]
    (rebac/clear-relations!)
    (rebac/clear-relation-rewrites!)
    (f)
    (rebac/clear-relations!)
    (rebac/clear-relation-rewrites!)))

(deftest direct-relation-tuples-test
  (let [subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (is (false? (rebac/has-relation? subject "viewer" resource)))
    (is (= {:subject {:class "Person" :id "alice"}
            :relation "viewer"
            :resource {:class "Document" :id "doc-1"}}
           (rebac/add-relation! subject "viewer" resource)))
    (is (true? (rebac/has-relation? subject "viewer" resource)))
    (is (false? (rebac/has-relation? subject "editor" resource)))
    (rebac/remove-relation! subject "viewer" resource)
    (is (false? (rebac/has-relation? subject "viewer" resource)))))

(deftest inherited-resource-relation-test
  (let [subject {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}
        folder {:class "Folder" :id "folder-1"}
        workspace {:class "Workspace" :id "workspace-1"}]
    (rebac/add-relation! document "parent" folder)
    (rebac/add-relation! folder "parent" workspace)
    (rebac/add-relation! subject "viewer" workspace)
    (is (true? (rebac/has-relation? subject "viewer" workspace)))
    (is (true? (rebac/has-relation? subject "viewer" folder)))
    (is (true? (rebac/has-relation? subject "viewer" document)))
    (is (false? (rebac/has-relation? subject "editor" document)))
    (is (false? (rebac/has-relation? subject "viewer" document {:inherited false})))))

(deftest explain-inherited-relation-test
  (let [subject {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}
        folder {:class "Folder" :id "folder-1"}]
    (rebac/add-relation! document "parent" folder)
    (rebac/add-relation! subject "viewer" folder)
    (is (= {:allowed true
            :subject {:class "Person" :id "alice"}
            :relation "viewer"
            :resource {:class "Document" :id "doc-1"}
            :matchedSubject {:class "Person" :id "alice"}
            :matchedRelation "viewer"
            :matchedResource {:class "Folder" :id "folder-1"}
            :inherited true
            :path [{:class "Document" :id "doc-1"}
                   {:class "Folder" :id "folder-1"}]}
           (rebac/explain-relation subject "viewer" document)))))

(deftest explain-missing-relation-test
  (let [subject {:class "Person" :id "alice"}
        document {:class "Document" :id "doc-1"}]
    (is (= {:allowed false
            :subject {:class "Person" :id "alice"}
            :relation "viewer"
            :resource {:class "Document" :id "doc-1"}}
           (rebac/explain-relation subject "viewer" document)))))

(deftest group-membership-relation-test
  (let [subject {:class "Person" :id "alice"}
        team {:class "Group" :id "team-a"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/add-relation! subject "member" team)
    (rebac/add-relation! team "viewer" resource)
    (is (true? (rebac/has-relation? subject "viewer" resource)))
    (is (false? (rebac/has-relation? subject "viewer" resource {:inherited false})))
    (is (= {:allowed true
            :subject {:class "Person" :id "alice"}
            :relation "viewer"
            :resource {:class "Document" :id "doc-1"}
            :matchedSubject {:class "Group" :id "team-a"}
            :matchedRelation "viewer"
            :matchedResource {:class "Document" :id "doc-1"}
            :inherited true
            :path [{:class "Document" :id "doc-1"}]
            :subjectPath [{:class "Person" :id "alice"}
                          {:class "Group" :id "team-a"}]}
           (rebac/explain-relation subject "viewer" resource)))))

(deftest nested-group-membership-relation-test
  (let [subject {:class "Person" :id "alice"}
        team {:class "Group" :id "team-a"}
        org-admins {:class "Group" :id "org-admins"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/add-relation! subject "member" team)
    (rebac/add-relation! team "member" org-admins)
    (rebac/add-relation! org-admins "viewer" resource)
    (is (true? (rebac/has-relation? subject "viewer" resource)))
    (is (= [{:class "Person" :id "alice"}
            {:class "Group" :id "team-a"}
            {:class "Group" :id "org-admins"}]
           (:subjectPath (rebac/explain-relation subject "viewer" resource))))))

(deftest relation-rewrite-test
  (let [subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/set-relation-rewrite! "can-read" ["viewer" "editor"])
    (rebac/add-relation! subject "viewer" resource)
    (is (true? (rebac/has-relation? subject "can-read" resource)))
    (is (= {:allowed true
            :subject {:class "Person" :id "alice"}
            :relation "can-read"
            :resource {:class "Document" :id "doc-1"}
            :matchedSubject {:class "Person" :id "alice"}
            :matchedRelation "viewer"
            :matchedResource {:class "Document" :id "doc-1"}
            :inherited true
            :path [{:class "Document" :id "doc-1"}]
            :relationPath ["can-read" "viewer"]}
           (rebac/explain-relation subject "can-read" resource)))))

(deftest relation-rewrites-are-tenant-isolated-test
  (let [subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/with-tenant "tenant-a"
      (rebac/set-relation-rewrite! "can-read" ["viewer"])
      (rebac/add-relation! subject "viewer" resource)
      (is (true? (rebac/has-relation? subject "can-read" resource))))
    (rebac/with-tenant "tenant-b"
      (rebac/set-relation-rewrite! "can-read" ["editor"])
      (rebac/add-relation! subject "viewer" resource)
      (is (false? (rebac/has-relation? subject "can-read" resource)))
      (is (= {"can-read" ["editor"]} (rebac/list-relation-rewrites))))
    (rebac/with-tenant "tenant-a"
      (is (= {"can-read" ["viewer"]} (rebac/list-relation-rewrites))))))

(deftest relation-rewrite-stops-on-cycles-test
  (let [subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (rebac/set-relation-rewrite! "can-read" ["readable"])
    (rebac/set-relation-rewrite! "readable" ["can-read" "viewer"])
    (rebac/add-relation! subject "viewer" resource)
    (is (true? (rebac/has-relation? subject "can-read" resource)))
    (is (= ["can-read" "readable" "viewer"]
           (:relationPath (rebac/explain-relation subject "can-read" resource))))))

(deftest list-accessible-resources-includes-rewrites-groups-and-descendants-test
  (let [alice {:class "Person" :id "alice"}
        team {:class "Group" :id "team-a"}
        folder {:class "Folder" :id "folder-1"}
        doc-1 {:class "Document" :id "doc-1"}
        doc-2 {:class "Document" :id "doc-2"}]
    (rebac/set-relation-rewrite! "can-read" ["viewer"])
    (rebac/add-relation! alice "member" team)
    (rebac/add-relation! team "viewer" folder)
    (rebac/add-relation! doc-1 "parent" folder)
    (rebac/add-relation! doc-2 "parent" folder)
    (is (= [doc-1 doc-2]
           (rebac/list-accessible-resources alice
                                            "can-read"
                                            {:resource-class "Document"})))
    (is (empty? (rebac/list-accessible-resources alice
                                                 "can-read"
                                                 {:resource-class "Document"
                                                  :inherited false})))))

(deftest list-authorized-subjects-includes-nested-group-members-test
  (let [alice {:class "Person" :id "alice"}
        bob {:class "Person" :id "bob"}
        team {:class "Group" :id "team-a"}
        org-admins {:class "Group" :id "org-admins"}
        folder {:class "Folder" :id "folder-1"}
        doc {:class "Document" :id "doc-1"}]
    (rebac/set-relation-rewrite! "can-read" ["viewer"])
    (rebac/add-relation! alice "member" team)
    (rebac/add-relation! team "member" org-admins)
    (rebac/add-relation! bob "member" org-admins)
    (rebac/add-relation! org-admins "viewer" folder)
    (rebac/add-relation! doc "parent" folder)
    (is (= [alice bob]
           (rebac/list-authorized-subjects doc
                                           "can-read"
                                           {:subject-class "Person"})))))

(deftest generic-relation-traversal-follows-explicit-path-test
  (let [alice {:class "Person" :id "alice"}
        team {:class "Group" :id "team-a"}
        workspace {:class "Workspace" :id "workspace-1"}
        folder {:class "Folder" :id "folder-1"}
        doc {:class "Document" :id "doc-1"}]
    (rebac/add-relation! alice "member" team)
    (rebac/add-relation! team "viewer" workspace)
    (rebac/add-relation! folder "parent" workspace)
    (rebac/add-relation! doc "parent" folder)
    (is (= {:start alice
            :steps [{:relation "member" :direction "out"}
                    {:relation "viewer" :direction "out"}
                    {:relation "parent" :direction "in"}
                    {:relation "parent" :direction "in"}]
            :entities [doc]
            :count 1
            :paths [{:entity doc
                     :path [{:entity alice}
                            {:relation "member" :direction "out" :entity team}
                            {:relation "viewer" :direction "out" :entity workspace}
                            {:relation "parent" :direction "in" :entity folder}
                            {:relation "parent" :direction "in" :entity doc}]}]}
           (rebac/traverse-relations alice
                                     ["member"
                                      "viewer"
                                      {:relation "parent" :direction "in"}
                                      {:relation "parent" :direction "in"}]
                                     {:target-class "Document"})))))

(deftest generic-relation-traversal-expands-rewrites-test
  (let [alice {:class "Person" :id "alice"}
        doc {:class "Document" :id "doc-1"}]
    (rebac/set-relation-rewrite! "can-read" ["viewer"])
    (rebac/add-relation! alice "viewer" doc)
    (is (= [doc]
           (:entities (rebac/traverse-relations alice
                                                ["can-read"]
                                                {:target-class "Document"}))))
    (is (empty? (:entities (rebac/traverse-relations alice
                                                     ["can-read"]
                                                     {:target-class "Document"
                                                      :expand-rewrites false}))))))

(deftest durable-relations-survive-reinit-test
  (let [test-db {:classname "org.h2.Driver"
                 :subprotocol "h2:mem"
                 :subname "rebac-test;DB_CLOSE_DELAY=-1"
                 :user "sa"
                 :password ""}
        subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (with-redefs-fn {#'rebac/db test-db
                     #'rebac/persistence-enabled? (atom false)}
      (fn []
        (try
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATIONS"])
          (rebac/init!)
          (rebac/clear-relations! {:persist true})
          (rebac/add-relation! subject "viewer" resource)
          (rebac/clear-relations!)
          (is (false? (rebac/has-relation? subject "viewer" resource)))
          (rebac/init!)
          (is (true? (rebac/has-relation? subject "viewer" resource)))
          (finally
            (rebac/clear-relations! {:persist true})))))))

(deftest durable-relations-are-isolated-by-tenant-test
  (let [test-db {:classname "org.h2.Driver"
                 :subprotocol "h2:mem"
                 :subname "rebac-tenant-test;DB_CLOSE_DELAY=-1"
                 :user "sa"
                 :password ""}
        subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (with-redefs-fn {#'rebac/db test-db
                     #'rebac/persistence-enabled? (atom false)}
      (fn []
        (try
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATIONS"])
          (rebac/init!)
          (rebac/with-tenant "tenant-a" (rebac/add-relation! subject "viewer" resource))
          (rebac/with-tenant "tenant-b" (rebac/add-relation! subject "viewer" resource))
          (rebac/clear-relations!)
          (rebac/init!)
          (is (true? (rebac/with-tenant "tenant-a"
                       (rebac/has-relation? subject "viewer" resource))))
          (is (true? (rebac/with-tenant "tenant-b"
                       (rebac/has-relation? subject "viewer" resource))))
          (is (= 1 (count (rebac/with-tenant "tenant-a" (rebac/list-relations)))))
          (is (= 1 (count (rebac/with-tenant "tenant-b" (rebac/list-relations)))))
          (finally
            (rebac/clear-relations! {:persist true})))))))

(deftest projection-metadata-survives-reinit-and-expiration-revokes-test
  (let [test-db {:classname "org.h2.Driver"
                 :subprotocol "h2:mem"
                 :subname "rebac-metadata-test;DB_CLOSE_DELAY=-1"
                 :user "sa"
                 :password ""}
        subject {:class "Person" :id "alice"}
        resource {:class "Document" :id "doc-1"}]
    (with-redefs-fn {#'rebac/db test-db
                     #'rebac/persistence-enabled? (atom false)}
      (fn []
        (try
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATIONS"])
          (rebac/init!)
          (rebac/with-tenant "tenant-a"
            (rebac/add-relation! subject "viewer" resource
                                 {:source "iam" :sourceEventId "event-42"
                                  :sourceVersion 48 :occurredAt "2026-08-13T10:00:00Z"}))
          (rebac/clear-relations!)
          (rebac/init!)
          (let [tuple (first (rebac/with-tenant "tenant-a" (rebac/list-relations)))]
            (is (= {:source "iam" :sourceEventId "event-42"
                    :sourceVersion "48" :occurredAt "2026-08-13T10:00:00Z"}
                   (:projection tuple))))
          (rebac/with-tenant "tenant-a"
            (rebac/add-relation! subject "viewer" resource
                                 {:source "iam" :expiresAt "2020-01-01T00:00:00Z"}))
          (is (false? (rebac/with-tenant "tenant-a"
                        (rebac/has-relation? subject "viewer" resource))))
          (finally
            (rebac/clear-relations! {:persist true})))))))

(deftest projection-events-are-idempotent-and-persist-provenance-test
  (let [test-db {:classname "org.h2.Driver"
                 :subprotocol "h2:mem"
                 :subname "rebac-events-test;DB_CLOSE_DELAY=-1"
                 :user "sa"
                 :password ""}
        event {:eventId "event-upsert-1"
               :eventType "authorization.relationship.upserted"
               :tenantId "tenant-a"
               :source "iam"
               :version 48
               :occurredAt "2026-08-13T10:00:00Z"
               :tuple {:subject {:class "Person" :id "alice"}
                       :relation "member"
                       :resource {:class "Group" :id "finance"}}}]
    (with-redefs-fn {#'rebac/db test-db
                     #'rebac/persistence-enabled? (atom false)}
      (fn []
        (try
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATIONS"])
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_PROJECTION_EVENTS"])
          (rebac/init!)
          (is (= :applied (:status (rebac/apply-projection-event! event))))
          (is (= :duplicate (:status (rebac/apply-projection-event! event))))
          (is (true? (rebac/with-tenant "tenant-a"
                       (rebac/has-relation? {:class "Person" :id "alice"}
                                            "member"
                                            {:class "Group" :id "finance"}))))
          (is (= "iam" (get-in (first (rebac/with-tenant "tenant-a" (rebac/list-relations)))
                                [:projection :source])))
          (is (= "48" (get-in (first (rebac/with-tenant "tenant-a" (rebac/list-relations)))
                                [:projection :sourceVersion])))
          (is (= :applied
                 (:status (rebac/apply-projection-event!
                           (assoc event :eventId "event-delete-2"
                                  :eventType "authorization.relationship.deleted"
                                  :version 50)))))
          (is (= :stale
                 (:status (rebac/apply-projection-event!
                           (assoc event :eventId "event-old-3" :version 49)))))
          (is (false? (rebac/with-tenant "tenant-a"
                        (rebac/has-relation? {:class "Person" :id "alice"}
                                             "member"
                                             {:class "Group" :id "finance"}))))
          (finally
            (rebac/clear-relations! {:persist true})))))))

(deftest projection-subject-and-resource-deletions-revoke-their-tuples
  (let [alice {:class "Person" :id "alice"}
        bob {:class "Person" :id "bob"}
        doc {:class "Document" :id "doc-1"}
        base {:tenantId "tenant-a" :source "documents" :occurredAt "2026-08-13T10:00:00Z"}]
    (rebac/with-tenant "tenant-a"
      (rebac/add-relation! alice "viewer" doc {:source "documents"}))
    (is (= 1 (:deletedCount
              (rebac/apply-projection-event!
               (merge base {:eventId "subject-delete-1" :eventType "authorization.subject.deleted" :version 4
                            :subject alice})))))
    (rebac/with-tenant "tenant-a"
      (rebac/add-relation! alice "viewer" doc {:source "documents"})
      (rebac/add-relation! bob "viewer" doc {:source "documents"}))
    (is (= 2 (:deletedCount
              (rebac/apply-projection-event!
               (merge base {:eventId "resource-delete-1" :eventType "authorization.resource.deleted" :version 5
                            :resource doc})))))
    (is (empty? (rebac/with-tenant "tenant-a" (rebac/list-relations))))))

(deftest reconciliation-reports-missing-and-obsolete-tuples
  (let [alice {:class "Person" :id "alice"}
        bob {:class "Person" :id "bob"}
        document {:class "Document" :id "doc-1"}]
    (rebac/with-tenant "tenant-a"
      (rebac/add-relation! alice "viewer" document {:source "documents" :sourceVersion "1"})
      (rebac/add-relation! bob "viewer" document {:source "documents"})
      (let [report (rebac/reconcile-snapshot "documents"
                                             [{:subject alice :relation "viewer" :resource document :sourceVersion "2"}
                                              {:subject {:class "Person" :id "carol"} :relation "viewer" :resource document}])]
        (is (= 1 (count (:missing report))))
        (is (= "carol" (get-in report [:missing 0 :subject :id])))
        (is (= 1 (count (:obsolete report))))
        (is (= "bob" (get-in report [:obsolete 0 :subject :id])))
        (is (= 1 (count (:conflicts report))))
        (is (= "2" (get-in report [:conflicts 0 :expected :projection :sourceVersion])))))))

(deftest durable-rewrites-survive-reinit-test
  (let [test-db {:classname "org.h2.Driver"
                 :subprotocol "h2:mem"
                 :subname "rebac-rewrite-test;DB_CLOSE_DELAY=-1"
                 :user "sa"
                 :password ""}]
    (with-redefs-fn {#'rebac/db test-db
                     #'rebac/persistence-enabled? (atom false)}
      (fn []
        (try
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATION_REWRITES"])
          (jdbc/execute! test-db ["DROP TABLE IF EXISTS REBAC_RELATIONS"])
          (rebac/init!)
          (rebac/clear-relation-rewrites! {:persist true})
          (rebac/set-relation-rewrite! "can-read" ["viewer" "editor"])
          (rebac/clear-relation-rewrites!)
          (is (empty? (rebac/list-relation-rewrites)))
          (rebac/init!)
          (is (= {"can-read" ["editor" "viewer"]}
                 (rebac/list-relation-rewrites)))
          (finally
            (rebac/clear-relation-rewrites! {:persist true})))))))

(deftest inherited-resource-relation-stops-on-cycles-test
  (let [subject {:class "Person" :id "alice"}
        folder-a {:class "Folder" :id "a"}
        folder-b {:class "Folder" :id "b"}]
    (rebac/add-relation! folder-a "parent" folder-b)
    (rebac/add-relation! folder-b "parent" folder-a)
    (rebac/add-relation! subject "viewer" folder-b)
    (is (true? (rebac/has-relation? subject "viewer" folder-a)))))
