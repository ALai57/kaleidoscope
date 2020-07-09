(ns andrewslai.cljs.article
  (:require [clojure.string :as str]
            [hickory.core :as h]
            [hickory.convert :refer [hickory-to-hiccup]]
            [hickory.select :as hs]
            [re-frame.core :refer [subscribe]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-timestamp [active-content]
  (when-let [ts (get-in active-content [:article :timestamp])]
    (.toLocaleDateString ts)))

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

(defn format-content [content]
  [:div#article-content
   (when content
     (->> content
          hickory-to-hiccup
          hiccup->sablono))])

(defn insert-dynamic-js! [content]
  (when-not (empty? content)
    (map format-js content)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fully formatted primary content
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn primary-content
  "Renders article content by parsing HTML (as a string) into Hickory, a CLJS
  readable form. Incoming HTML is separated into two parts, (1) traditional HTML
  that can be rendered with Hiccup and (2) script tags and JS that need to be
  dynamically added to the DOM to render properly"
  []
  (let [{:keys [article timestamp author title content] :as active-content}
        @(subscribe [:active-content])

        hickory-content (->> content
                             h/parse-fragment
                             (map h/as-hickory)
                             first)
        html-content    (->> hickory-content
                             (hs/select (hs/and (hs/not (hs/tag :script))))
                             first)
        js-content      (->> hickory-content
                             (hs/select (hs/tag :script)))]
    (if active-content
      [:div#goodies
       [:h2.article-title title]
       [:div.article-subheading (str "Author: " author)]
       [:div.article-subheading (format-timestamp active-content)]
       [:div.line]
       [:br][:br]
       [:div (format-content html-content)]
       (insert-dynamic-js! (map (comp :src :attrs) js-content))]
      [:div#goodies])))

(comment
  (require '[re-frame.db :refer [app-db]])
  (map h/as-hiccup (h/parse-fragment (:content (get-content (:active-content @app-db)))))
  (format-js "test-paragraph.js")
  (get-js (:active-content @app-db))
  )

(comment
  (hiccup->sablono (first (map h/as-hiccup (h/parse-fragment "<div><font color=\"#ce181e\"><font face=\"Ubuntu Mono\"><font size=\"5\"><b>A basic test</b></font></font></font><p style=\"color:red\">Many of the</p><p>Usually, that database</p></div>"))))
  )
