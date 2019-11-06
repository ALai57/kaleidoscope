(ns andrewslai.cljs.article
  (:require [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe]]
            [clojure.string :as str]

            [hickory.core :as h]
            [cljsjs.react-bootstrap]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-title [active-content]
  (get-in active-content [:article :title]))

(defn get-content [active-content]
  (first (get-in active-content [:article :content])))

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
  ^{:key (str js-script)} [:div])

(defn format-content [{:keys [content]}]
  [:div#article-content
   (when content
     (first (map h/as-hiccup (h/parse-fragment content))))])

(defn insert-dynamic-js! [{:keys [dynamicjs]}]
  (map format-js dynamicjs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fully formatted primary content
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn primary-content
  []
  (let [active-content (subscribe [:active-content])]
    [:div#goodies
     (format-title (get-title @active-content))
     (format-content (get-content @active-content))
     (insert-dynamic-js! (get-content @active-content))
     ]))

(comment
  (require '[re-frame.db :refer [app-db]])
  (map h/as-hiccup (h/parse-fragment (:content (get-content (:active-content @app-db)))))
  (format-js "test-paragraph.js")
  (get-js (:active-content @app-db))
  )
