(ns full-stack-template.example
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [ajax.core :refer [GET POST]]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [reagent.core :as reagent]
            [sablono.core :as sab]))

(.log js/console (str "----------------------\n"
                      "Starting App!"
                      "\n----------------------\n"))



(def table-data (reagent/atom ^{:key :asdf} []))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Table formatting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-status [s]
  (if (= s "HEALTHY")
    {:style {:background "lightgreen"}}
    {:style {:background "lightpink"}}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Plaid dashboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn error-handler [{:keys [status status-text]}]
  (.log js/console
        (str "something bad happened: " status " " status-text)))

(defn render-dashboard [response]
  (reset! table-data response))

(GET "/mock-data"
     {:handler render-dashboard
      :error-handler error-handler})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Prototyping
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def app-state (reagent/atom {:sort-val :Name :ascending true}))

(defn update-sort-value [new-val]
  (if (= new-val (:sort-val @app-state))
    (swap! app-state update-in [:ascending] not)
    (swap! app-state assoc :ascending true))
  (swap! app-state assoc :sort-val new-val))

(defn sorted-contents []
  (let [sorted-contents (sort-by (:sort-val @app-state) @table-data)]
    (if (:ascending @app-state)
      sorted-contents
      (rseq sorted-contents))))

(defn column-header [w k n]
  [:th.text-center
   (conj w {:on-click #(update-sort-value k)}) n])

(defn table-entry [options k data]
  [:td.text-center
   options (k data)])

(defn table []
  [:div.container
   [:table.table.table-sm.table-striped
    {:style {:width "800px"}}
    [:tr.d
     (column-header {} :name "Name")
     (column-header {} :status "Status")
     (column-header {} :error_institution "Institution Error")
     (column-header {} :error_plaid "Plaid Error")
     (column-header {} :success "Success")
     (column-header {} :last_status_change "Last Status Change")]
    [:tbody
     (for [person (sorted-contents)]
       ^{:key (:id person)}
       [:tr.d
        (table-entry (conj {:width "30%"}
                           {:style {:font-weight "bold"}}) :name person)
        (table-entry (conj {:width "10%"}
                           (format-status (:status person))) :status person)
        (table-entry {:width "10%"} :error_institution person)
        (table-entry {:width "10%"} :error_plaid person)
        (table-entry {:width "10%"} :success person)
        (table-entry {:width "30%"} :last_status_change person)])]]])

(defn main []
  (reagent/render [table]
                  (.getElementById js/document "app")))

(main)

