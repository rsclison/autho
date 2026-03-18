(ns autho.tracing
  "Request tracing via correlation IDs propagated through mulog context.
   Every incoming request is assigned a unique request-id that flows
   through all structured log events, enabling end-to-end correlation."
  (:require [com.brunobonacci.mulog :as u])
  (:import (java.util UUID)))

(defn generate-request-id []
  (str (UUID/randomUUID)))

(defn wrap-tracing
  "Ring middleware that assigns a unique request-id to every request.
   The ID is taken from X-Request-Id header when present (proxy already set it),
   or generated as a UUID otherwise.
   The ID is propagated to all mulog events and added to the response headers."
  [handler]
  (fn [request]
    (let [request-id (or (get-in request [:headers "x-request-id"])
                         (generate-request-id))
          request    (assoc request :request-id request-id)]
      (u/with-context {:request-id request-id}
        (let [response (handler request)]
          (assoc-in response [:headers "X-Request-Id"] request-id))))))
