(ns autho.api.v1
  "RESTful API v1 routes for autho authorization server.
   Provides standardized endpoints with proper HTTP semantics."
  (:require [compojure.core :refer [defroutes context GET POST PUT DELETE]]
            [clojure.string :as str]
            [autho.api.handlers :as handlers]
            [autho.api.subject-handlers :as subject-handlers]
            [autho.api.resource-handlers :as resource-handlers]))

(defn- decode-query-string [query-string]
  (when (seq query-string)
    (->> (str/split query-string #"&")
         (map (fn [pair]
                (let [[k v] (str/split pair #"=" 2)
                      decode #(java.net.URLDecoder/decode (or % "") "UTF-8")]
                  [(decode k) (decode v)])))
         (into {}))))

(defn- request-param [request key]
  (let [k (name key)
        decoded-query (decode-query-string (:query-string request))]
    (or (get-in request [:query-params k])
        (get-in request [:query-params key])
        (get-in request [:params k])
        (get-in request [:params key])
        (get decoded-query k)
        (get decoded-query (keyword k)))))

(defn- plane-call
  [_plane thunk]
  (thunk))

;; =============================================================================
;; v1 API Routes
;; =============================================================================

(defroutes v1-routes
  ;; ===================================================================
  ;; Authorization Endpoints
  ;; ===================================================================
  (context "/authz" []
    (POST "/decisions" request
          (plane-call :data #(handlers/is-authorized request)))

    (POST "/subjects" request
          (plane-call :data #(handlers/who-authorized request)))

    (POST "/permissions" request
          (plane-call :data #(handlers/what-authorized request)))

    (POST "/explain" request
          (plane-call :data #(handlers/explain-decision request)))

    (POST "/simulate" request
          (plane-call :data #(handlers/simulate-decision request)))

    (POST "/shadow" request
          (plane-call :data #(handlers/shadow-decision request)))

    (POST "/batch" request
          (plane-call :data #(handlers/batch-decisions request))))

  ;; ===================================================================
  ;; Policy Management Endpoints
  ;; ===================================================================
  (context "/policies" []
    (GET "/" request
         (plane-call :control #(handlers/list-policies request)))

    (POST "/" request
          (plane-call :control #(handlers/create-policy request)))

    (POST "/import" request
          (plane-call :control #(handlers/import-yaml-policies request)))

    (POST "/bundles/verify" request
          (plane-call :control #(handlers/verify-policy-bundle request)))

    (POST "/bundles/apply" request
          (plane-call :control #(handlers/apply-policy-bundle request)))

    (GET "/:resource-class/versions" [resource-class]
         (plane-call :control #(handlers/list-policy-versions resource-class)))

    (GET "/:resource-class/versions/:version/bundle" [resource-class version :as request]
         (plane-call :control #(handlers/export-policy-version-bundle resource-class version request)))

    (GET "/:resource-class/versions/:version" [resource-class version]
         (plane-call :control #(handlers/get-policy-version resource-class version)))

    (GET "/:resource-class/diff" [resource-class :as request]
         (plane-call :control #(handlers/diff-policy-versions resource-class
                                                              (request-param request :from)
                                                              (request-param request :to))))

    (GET "/:resource-class/timeline" [resource-class :as request]
         (plane-call :control #(handlers/get-policy-change-timeline resource-class request)))

    (POST "/:resource-class/impact" [resource-class :as request]
          (plane-call :control #(handlers/analyze-policy-impact resource-class request)))

    (GET "/risk-profiles" []
         (plane-call :control handlers/list-policy-risk-profiles))

    (GET "/risk-profiles/revisions" []
         (plane-call :control handlers/list-policy-risk-profile-revisions))

    (PUT "/risk-profiles/default" request
         (plane-call :control #(handlers/upsert-policy-risk-profile "default" "*" request)))

    (DELETE "/risk-profiles/default" request
            (plane-call :control #(handlers/delete-policy-risk-profile "default" "*" request)))

    (PUT "/risk-profiles/environments/:environment" [environment :as request]
         (plane-call :control #(handlers/upsert-policy-risk-profile "environment" environment request)))

    (DELETE "/risk-profiles/environments/:environment" [environment :as request]
            (plane-call :control #(handlers/delete-policy-risk-profile "environment" environment request)))

    (PUT "/risk-profiles/resource-classes/:resource-class" [resource-class :as request]
         (plane-call :control #(handlers/upsert-policy-risk-profile "resource_class" resource-class request)))

    (DELETE "/risk-profiles/resource-classes/:resource-class" [resource-class :as request]
            (plane-call :control #(handlers/delete-policy-risk-profile "resource_class" resource-class request)))

    (GET "/:resource-class/impact/history" [resource-class]
         (plane-call :control #(handlers/list-policy-impact-history resource-class)))

    (GET "/:resource-class/impact/history/:analysis-id" [resource-class analysis-id]
         (plane-call :control #(handlers/get-policy-impact-history-entry resource-class analysis-id)))


    (POST "/:resource-class/impact/history/:analysis-id/review" [resource-class analysis-id :as request]
          (plane-call :control #(handlers/update-policy-impact-review resource-class analysis-id request)))

    (POST "/:resource-class/impact/history/:analysis-id/rollout" [resource-class analysis-id :as request]
          (plane-call :control #(handlers/rollout-policy-impact-preview resource-class analysis-id request)))

    (POST "/:resource-class/rollback/:version" [resource-class version :as request]
          (plane-call :control #(handlers/rollback-policy resource-class version request)))

    (POST "/:resource-class/validate" [resource-class :as request]
          (plane-call :control #(handlers/validate-policy resource-class request)))

    (GET "/:resource-class" [resource-class :as request]
         (plane-call :control #(handlers/get-policy resource-class request)))

    (PUT "/:resource-class" [resource-class :as request]
          (plane-call :control #(handlers/update-policy resource-class request)))

    (DELETE "/:resource-class" [resource-class :as request]
            (plane-call :control #(handlers/delete-policy resource-class request))))

  ;; ===================================================================
  ;; Evidence Endpoints
  ;; ===================================================================
  (context "/evidence" []
    (GET "/" request
         (plane-call :evidence #(handlers/export-evidence-package request))))

  (context "/evidence" []
    (POST "/verify" request
          (plane-call :evidence #(handlers/verify-evidence-package request))))

  ;; ===================================================================
  ;; Relationship Management Endpoints
  ;; ===================================================================
  (context "/relations" []
    (GET "/" []
         (plane-call :control handlers/list-relations))

    (GET "/rewrites" []
         (plane-call :control handlers/list-relation-rewrites))

    (PUT "/rewrites/:relation" [relation :as request]
         (plane-call :control #(handlers/upsert-relation-rewrite relation request)))

    (DELETE "/rewrites/:relation" [relation :as request]
            (plane-call :control #(handlers/delete-relation-rewrite relation request)))

    (POST "/" request
          (plane-call :control #(handlers/create-relation request)))

    (POST "/check" request
          (plane-call :control #(handlers/check-relation request)))

    (POST "/list-objects" request
          (plane-call :control #(handlers/list-relation-objects request)))

    (POST "/list-subjects" request
          (plane-call :control #(handlers/list-relation-subjects request)))

    (POST "/traverse" request
          (plane-call :control #(handlers/traverse-relations request)))

    (DELETE "/" request
            (plane-call :control #(handlers/delete-relation request))))

  ;; ===================================================================
  ;; Cache Management Endpoints
  ;; ===================================================================
  (context "/cache" []
    (GET "/stats" []
         (plane-call :control handlers/get-cache-stats))

    (DELETE "/" []
            (plane-call :control handlers/clear-cache))

    (DELETE "/:type/:key" [type key]
            (plane-call :control #(handlers/invalidate-cache-entry type key))))

  ;; ===================================================================
  ;; Subject Management Endpoints
  ;; ===================================================================
  (context "/subjects" []
    (GET "/" request
         (plane-call :data #(subject-handlers/list-subjects request)))

    (GET "/:id" [id]
         (plane-call :data #(subject-handlers/get-subject id)))

    (GET "/search" request
         (plane-call :data #(subject-handlers/search-subjects-handler request)))

    (POST "/batch-get" request
          (plane-call :data #(subject-handlers/batch-get-subjects request))))

  ;; ===================================================================
  ;; Resource Management Endpoints
  ;; ===================================================================
  (context "/resources" []
    (GET "/" []
         (plane-call :data resource-handlers/list-resource-classes))

    (GET "/search" request
         (plane-call :data #(resource-handlers/search-resources-handler request)))

    (GET "/:class" [class :as request]
         (plane-call :data #(resource-handlers/list-resources-by-class class request)))

    (GET "/:class/:id" [class id]
         (plane-call :data #(resource-handlers/get-resource class id)))

    (POST "/batch-get" request
          (plane-call :data #(resource-handlers/batch-get-resources request))))

  )



