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
          (handlers/explain-decision))

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

    (GET "/:resource-class" [resource-class]
         (handlers/get-policy resource-class))

    (PUT "/:resource-class" [resource-class :as request]
          (handlers/update-policy resource-class request))

    (DELETE "/:resource-class" [resource-class]
            (handlers/delete-policy resource-class)))

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
