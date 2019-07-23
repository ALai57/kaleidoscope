(ns andrewslai.cljs.article
  (:require [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe]]
            [clojure.string :as str]

            [cljsjs.react-bootstrap]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-title [active-content]
  (get-in active-content [:article :title]))

(defn get-content [active-content]
  (get-in active-content [:article :content]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Formatting title, JS and content
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn format-title [title]
  [:h2.article-title title])

(defn format-js [js-script]
  (.appendChild (.getElementById js/document "primary-content")
                (doto (.createElement js/document "script")
                  (-> (.setAttribute "id" js-script))
                  (-> (.setAttribute "class" "dynamicjs"))
                  (-> (.setAttribute "src" (str "js/" js-script)))))
  [:div])

(defn format-content [content]
  (into
   [:div#article-content]
   (for [entry (sort-by :content_order content)]
     (condp = (:content_type entry)
       "text" ^{:key (:content_order entry)} [:p (:content entry)]
       "js" ^{:key (:content_order entry)} (format-js (:content entry))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fully formatted primary content
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn primary-content
  []
  (let [active-content (subscribe [:active-content])]
    [:div#goodies
     (format-title (get-title @active-content))
     (format-content (get-content @active-content))]))

