(ns autho.api.admin-ui
  "Hiccup-based HTML admin dashboard for autho authorization server.
   Provides read-only visibility into policies, audit log, and circuit breakers."
  (:require [autho.prp :as prp]
            [autho.audit :as audit]
            [hiccup2.core :as h]
            [clojure.string :as str])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------------------
;; Layout helpers
;; ---------------------------------------------------------------------------

(defn- html-response [body-hiccup]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "<!DOCTYPE html>"
                 (h/html
                  [:html {:lang "en"}
                   [:head
                    [:meta {:charset "UTF-8"}]
                    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
                    [:title "autho — Admin"]
                    [:style
                     "body{font-family:system-ui,sans-serif;margin:0;background:#f5f5f5;color:#222}"
                     ".nav{background:#1a1a2e;color:#fff;padding:12px 24px;display:flex;gap:24px;align-items:center}"
                     ".nav a{color:#8be9fd;text-decoration:none;font-weight:500}"
                     ".nav .brand{font-size:1.2rem;font-weight:700;color:#fff}"
                     ".container{max-width:1100px;margin:32px auto;padding:0 24px}"
                     "h1{font-size:1.5rem;margin-bottom:16px}"
                     "h2{font-size:1.1rem;border-bottom:2px solid #1a1a2e;padding-bottom:4px}"
                     ".card{background:#fff;border-radius:8px;padding:20px;margin-bottom:20px;box-shadow:0 1px 3px rgba(0,0,0,.1)}"
                     "table{width:100%;border-collapse:collapse;font-size:.9rem}"
                     "th{background:#1a1a2e;color:#fff;text-align:left;padding:8px 12px}"
                     "td{padding:8px 12px;border-bottom:1px solid #e5e5e5}"
                     "tr:hover td{background:#f0f8ff}"
                     ".badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:.78rem;font-weight:600}"
                     ".badge-allow{background:#d4edda;color:#155724}"
                     ".badge-deny{background:#f8d7da;color:#721c24}"
                     ".badge-open{background:#f8d7da;color:#721c24}"
                     ".badge-closed{background:#d4edda;color:#155724}"
                     ".badge-half-open{background:#fff3cd;color:#856404}"
                     ".stat{display:inline-block;background:#1a1a2e;color:#fff;padding:12px 20px;border-radius:8px;margin-right:12px;text-align:center}"
                     ".stat .num{font-size:1.8rem;font-weight:700;color:#8be9fd}"
                     ".stat .lbl{font-size:.8rem;text-transform:uppercase;opacity:.8}"]]
                   [:body
                    [:div.nav
                     [:span.brand "⚡ autho"]
                     [:a {:href "/admin/ui"} "Dashboard"]
                     [:a {:href "/admin/ui/policies"} "Policies"]
                     [:a {:href "/admin/ui/audit"} "Audit Log"]
                     [:a {:href "/admin/ui/circuit-breakers"} "Circuit Breakers"]
                     [:a {:href "/openapi.yaml"} "OpenAPI"]]
                    [:div.container
                     body-hiccup]]]))})

;; ---------------------------------------------------------------------------
;; Dashboard
;; ---------------------------------------------------------------------------

(defn dashboard-page []
  (let [policies      (prp/get-policies)
        policy-count  (count (if (map? policies) (vals policies) policies))
        audit-recent  (try (:items (audit/search {:page 1 :page-size 5}))
                           (catch Exception _ []))
        ts            (str (Instant/now))]
    (html-response
     [:div
      [:h1 "Dashboard"]
      [:p {:style "color:#666"} "Server time: " ts]
      [:div {:style "margin-bottom:24px"}
       [:div.stat [:div.num policy-count] [:div.lbl "Policies"]]
       [:div.stat [:div.num (count audit-recent)] [:div.lbl "Recent decisions"]]]
      [:div.card
       [:h2 "Recent Authorization Decisions"]
       (if (seq audit-recent)
         [:table
          [:thead [:tr [:th "Time"] [:th "Subject"] [:th "Resource"] [:th "Decision"]]]
          [:tbody
           (for [e audit-recent]
             [:tr
              [:td (str (:ts e))]
              [:td (:subject_id e)]
              [:td (:resource_class e)]
              [:td [:span {:class (str "badge badge-" (name (:decision e)))} (str/upper-case (name (:decision e)))]]])]]
         [:p {:style "color:#888"} "No recent decisions."])]])))

;; ---------------------------------------------------------------------------
;; Policies page
;; ---------------------------------------------------------------------------

(defn policies-page []
  (let [policies (prp/get-policies)
        rows     (if (map? policies) (vals policies) policies)]
    (html-response
     [:div
      [:h1 "Policies"]
      [:div.card
       (if (seq rows)
         [:table
          [:thead [:tr [:th "Resource Class"] [:th "Strategy"] [:th "Rules"]]]
          [:tbody
           (for [p rows]
             [:tr
              [:td (:resourceClass p)]
              [:td (str (:strategy p))]
              [:td (count (:rules p))]])]]
         [:p {:style "color:#888"} "No policies loaded."])]])))

;; ---------------------------------------------------------------------------
;; Audit page
;; ---------------------------------------------------------------------------

(defn audit-page [query-params]
  (let [subject-id     (get query-params "subjectId")
        resource-class (get query-params "resourceClass")
        decision       (when-let [d (get query-params "decision")] (keyword d))
        page           (or (some-> (get query-params "page") Long/parseLong) 1)
        result         (try (audit/search {:subject-id     subject-id
                                           :resource-class resource-class
                                           :decision       decision
                                           :page           page
                                           :page-size      20})
                            (catch Exception e {:items [] :error (.getMessage e)}))
        items          (:items result)
        total          (:total result 0)]
    (html-response
     [:div
      [:h1 "Audit Log"]
      [:div.card
       [:form {:method "get" :action "/admin/ui/audit" :style "display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px"}
        [:input {:type "text" :name "subjectId" :placeholder "Subject ID" :value (or subject-id "")
                 :style "padding:6px;border:1px solid #ccc;border-radius:4px"}]
        [:input {:type "text" :name "resourceClass" :placeholder "Resource Class" :value (or resource-class "")
                 :style "padding:6px;border:1px solid #ccc;border-radius:4px"}]
        [:select {:name "decision" :style "padding:6px;border:1px solid #ccc;border-radius:4px"}
         [:option {:value ""} "All decisions"]
         [:option {:value "allow" :selected (= decision :allow)} "Allow"]
         [:option {:value "deny"  :selected (= decision :deny)}  "Deny"]]
        [:button {:type "submit" :style "padding:6px 16px;background:#1a1a2e;color:#fff;border:none;border-radius:4px;cursor:pointer"}
         "Filter"]]
       (when (:error result)
         [:p {:style "color:red"} (:error result)])
       (if (seq items)
         [:div
          [:p {:style "color:#666;font-size:.9rem"} "Showing " (count items) " of " total " entries"]
          [:table
           [:thead [:tr [:th "Timestamp"] [:th "Request ID"] [:th "Subject"] [:th "Resource Class"] [:th "Operation"] [:th "Decision"]]]
           [:tbody
            (for [e items]
              [:tr
               [:td (str (:ts e))]
               [:td {:style "font-family:monospace;font-size:.8rem"} (str (:request_id e))]
               [:td (:subject_id e)]
               [:td (:resource_class e)]
               [:td (:operation e)]
               [:td [:span {:class (str "badge badge-" (name (:decision e)))} (str/upper-case (name (:decision e)))]]])]]]
         [:p {:style "color:#888"} "No audit entries found."])]
      [:div {:style "margin-top:8px"}
       (when (> page 1)
         [:a {:href (str "/admin/ui/audit?page=" (dec page)
                         (when subject-id (str "&subjectId=" subject-id))
                         (when resource-class (str "&resourceClass=" resource-class))
                         (when decision (str "&decision=" (name decision))))
              :style "margin-right:12px"}
          "← Previous"])
       (when (> total (* page 20))
         [:a {:href (str "/admin/ui/audit?page=" (inc page)
                         (when subject-id (str "&subjectId=" subject-id))
                         (when resource-class (str "&resourceClass=" resource-class))
                         (when decision (str "&decision=" (name decision))))}
          "Next →"])]])))

;; ---------------------------------------------------------------------------
;; Circuit breakers page
;; ---------------------------------------------------------------------------

(defn circuit-breakers-page [status-map]
  (html-response
   [:div
    [:h1 "Circuit Breakers"]
    [:div.card
     (if (seq status-map)
       [:table
        [:thead [:tr [:th "Endpoint / Key"] [:th "State"]]]
        [:tbody
         (for [[k state] (sort-by first status-map)]
           [:tr
            [:td {:style "font-family:monospace"} (str k)]
            [:td [:span {:class (str "badge badge-" (name state))} (str/upper-case (name state))]]])]]
       [:p {:style "color:#888"} "No circuit breakers registered yet."])]]))
