(ns autho.api.v1
  "RESTful API v1 routes for autho authorization server.
   Provides standardized endpoints with proper HTTP semantics."
  (:require [compojure.core :refer [defroutes context GET POST PUT DELETE]]
            [autho.api.handlers :as handlers]
            [autho.api.subject-handlers :as subject-handlers]
            [autho.api.resource-handlers :as resource-handlers]))

;; =============================================================================
;; v1 API Routes
;; =============================================================================

(defroutes v1-routes
  ;; ===================================================================
  ;; Authorization Endpoints
  ;; ===================================================================
  (context "/authz" []
    (POST "/decisions" request
          (handlers/is-authorized request))

    (POST "/subjects" request
          (handlers/who-authorized request))

    (POST "/permissions" request
          (handlers/what-authorized request))

    (POST "/explain" request
          (handlers/explain-decision request))

    (POST "/simulate" request
          (handlers/simulate-decision request))

    (POST "/shadow" request
          (handlers/shadow-decision request))

    (POST "/batch" request
          (handlers/batch-decisions)))

  ;; ===================================================================
  ;; Policy Management Endpoints
  ;; ===================================================================
  (context "/policies" []
    (GET "/" request
         (handlers/list-policies request))

    (POST "/" request
          (handlers/create-policy request))

    (POST "/import" request
          (handlers/import-yaml-policies request))

    (GET "/:resource-class/versions" [resource-class]
         (handlers/list-policy-versions resource-class))

    (GET "/:resource-class/versions/:version" [resource-class version]
         (handlers/get-policy-version resource-class version))

    (GET "/:resource-class/diff" [resource-class :as request]
         (handlers/diff-policy-versions resource-class
                                         (get-in request [:params :from])
                                         (get-in request [:params :to])))

    (GET "/:resource-class/timeline" [resource-class :as request]
         (handlers/get-policy-change-timeline resource-class request))

    (POST "/:resource-class/impact" [resource-class :as request]
          (handlers/analyze-policy-impact resource-class request))

    (GET "/risk-profiles" []
         (handlers/list-policy-risk-profiles))

    (GET "/risk-profiles/revisions" []
         (handlers/list-policy-risk-profile-revisions))

    (PUT "/risk-profiles/default" request
         (handlers/upsert-policy-risk-profile "default" "*" request))

    (DELETE "/risk-profiles/default" request
            (handlers/delete-policy-risk-profile "default" "*" request))

    (PUT "/risk-profiles/environments/:environment" [environment :as request]
         (handlers/upsert-policy-risk-profile "environment" environment request))

    (DELETE "/risk-profiles/environments/:environment" [environment :as request]
            (handlers/delete-policy-risk-profile "environment" environment request))

    (PUT "/risk-profiles/resource-classes/:resource-class" [resource-class :as request]
         (handlers/upsert-policy-risk-profile "resource_class" resource-class request))

    (DELETE "/risk-profiles/resource-classes/:resource-class" [resource-class :as request]
            (handlers/delete-policy-risk-profile "resource_class" resource-class request))

    (GET "/:resource-class/impact/history" [resource-class]
         (handlers/list-policy-impact-history resource-class))

    (GET "/:resource-class/impact/history/:analysis-id" [resource-class analysis-id]
         (handlers/get-policy-impact-history-entry resource-class analysis-id))


    (POST "/:resource-class/impact/history/:analysis-id/review" [resource-class analysis-id :as request]
          (handlers/update-policy-impact-review resource-class analysis-id request))

    (POST "/:resource-class/impact/history/:analysis-id/rollout" [resource-class analysis-id :as request]
          (handlers/rollout-policy-impact-preview resource-class analysis-id request))

    (POST "/:resource-class/rollback/:version" [resource-class version :as request]
          (handlers/rollback-policy resource-class version request))

    (POST "/:resource-class/validate" [resource-class :as request]
          (handlers/validate-policy resource-class request))

    (GET "/:resource-class" [resource-class :as request]
         (handlers/get-policy resource-class request))

    (PUT "/:resource-class" [resource-class :as request]
          (handlers/update-policy resource-class request))

    (DELETE "/:resource-class" [resource-class :as request]
            (handlers/delete-policy resource-class request)))

  ;; ===================================================================
  ;; Relationship Management Endpoints
  ;; ===================================================================
  (context "/relations" []
    (GET "/" []
         (handlers/list-relations))

    (GET "/rewrites" []
         (handlers/list-relation-rewrites))

    (PUT "/rewrites/:relation" [relation :as request]
         (handlers/upsert-relation-rewrite relation request))

    (DELETE "/rewrites/:relation" [relation :as request]
            (handlers/delete-relation-rewrite relation request))

    (POST "/" request
          (handlers/create-relation request))

    (POST "/check" request
          (handlers/check-relation request))

    (DELETE "/" request
            (handlers/delete-relation request)))

  ;; ===================================================================
  ;; Cache Management Endpoints
  ;; ===================================================================
  (context "/cache" []
    (GET "/stats" []
         (handlers/get-cache-stats))

    (DELETE "/" []
            (handlers/clear-cache))

    (DELETE "/:type/:key" [type key]
            (handlers/invalidate-cache-entry type key)))

  ;; ===================================================================
  ;; Subject Management Endpoints
  ;; ===================================================================
  (context "/subjects" []
    (GET "/" request
         (subject-handlers/list-subjects request))

    (GET "/:id" [id]
         (subject-handlers/get-subject id))

    (GET "/search" request
         (subject-handlers/search-subjects-handler request))

    (POST "/batch-get" request
          (subject-handlers/batch-get-subjects request)))

  ;; ===================================================================
  ;; Resource Management Endpoints
  ;; ===================================================================
  (context "/resources" []
    (GET "/" []
         (resource-handlers/list-resource-classes))

    (GET "/:class" [class :as request]
         (resource-handlers/list-resources-by-class class request))

    (GET "/:class/:id" [class id]
         (resource-handlers/get-resource class id))

    (GET "/search" request
         (resource-handlers/search-resources-handler request))

    (POST "/batch-get" request
          (resource-handlers/batch-get-resources request))))



