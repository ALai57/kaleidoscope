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


(defn string->tokens
  "Takes a string with syles and parses it into properties and value tokens"
  [style]
  {:pre [(string? style)]
   :post [(even? (count %))]}
  (->> (clojure.string/split style #";")
       (mapcat #(clojure.string/split % #":"))
       (map clojure.string/trim)))

(defn tokens->map
  "Takes a seq of tokens with the properties (even) and their values (odd)
   and returns a map of {properties values}"
  [tokens]
  {:pre [(even? (count tokens))]
   :post [(map? %)]}
  (zipmap (keep-indexed #(if (even? %1) %2) tokens)
          (keep-indexed #(if (odd? %1) %2) tokens)))

(defn style->map
  "Takes an inline style attribute stirng and converts it to a React Style map"
  [style]
  (if (empty? style)
    style
    (tokens->map (string->tokens style))))

(defn hiccup->sablono
  "Transforms a style inline attribute into a style map for React"
  [coll]
  (clojure.walk/postwalk
   (fn [x]
     (if (map? x)
       (update-in x [:style] style->map)
       x))
   coll))

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
     (->> content
          h/parse-fragment
          (map h/as-hiccup)
          first
          hiccup->sablono))])

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

(comment
  (hiccup->sablono (first (map h/as-hiccup (h/parse-fragment "<div><font color=\"#ce181e\"><font face=\"Ubuntu Mono\"><font size=\"5\"><b>A basic test</b></font></font></font><p style=\"color:red\">Many of the</p><p>Usually, that database</p></div>"))))
  )
