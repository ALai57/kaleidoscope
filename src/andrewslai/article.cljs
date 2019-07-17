(ns andrewslai.article
  (:require [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe]]
            [clojure.string :as str]

            [cljsjs.react-bootstrap]))

(defn format-title [content]
  (let [title (get-in content [:article :title])
        style (:metadata
               (first
                (get-in content [:article :content])))]
    [:h2.article-title style title]))

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


(defn primary-content
  []
  (let [active-content (subscribe [:active-content])]
    [:div#goodies
     (format-title @active-content)
     (format-content
      (get-in @active-content [:article :content]))]))

