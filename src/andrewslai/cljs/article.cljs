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
  [:div])

(defn format-content [{:keys [content]}]
  [:div#article-content
   (first (map h/as-hiccup (h/parse-fragment content)))])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fully formatted primary content
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn primary-content
  []
  (let [active-content (subscribe [:active-content])
        _ (println "content " (get-content @active-content))]
    [:div#goodies
     (format-title (get-title @active-content))
     (format-content (get-content @active-content))
     ]))

(comment
  (let [x {:article_id 1, :content "<h1>This is an example content piece</h1><h2>This is second example content piece</h2><p class=\"dynamicjs\"></p><script src=\"js/test-paragraph.js\"></script><p>This is fourth example content piece</p>"}
        content (:content x)]
    ;; (println "\n\n++++++++" (format-content x))
    (println (for [entry (map h/as-hiccup (h/parse-fragment content))]
               (println (str entry)))))
  (binding [*print-meta* true]
    (println ^{:key (str 23)} [:p {} "This is fourth example content piece"]))
  )
