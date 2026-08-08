(ns kaleidoscope.api.curation.parse.goodreads
  (:require [clojure.string :as str]))

(defn- extract-title [html]
  (when-let [[_ title] (re-find #"(?is)<title[^>]*>(.*?)</title>" html)]
    (-> title str/trim
        (str/replace #"(?i)\s*\|\s*Goodreads\s*$" "")
        not-empty)))

(defn- extract-author
  "Best-effort: the first element carrying an \"author\" class, e.g.
  <span class=\"author\">Cal Newport</span> or <a class=\"authorName\">...</a>."
  [html]
  (when-let [[_ author] (re-find #"(?is)class=\"[^\"]*\bauthor\w*\b[^\"]*\"[^>]*>\s*([^<]+?)\s*<" html)]
    (-> author str/trim not-empty)))

(defn parse-content
  "Thin Goodreads parse: pulls the book <title> (minus the trailing \"| Goodreads\")
  and a best-effort author name."
  [{:keys [raw]}]
  (let [html   (or raw "")
        title  (extract-title html)
        author (extract-author html)]
    (cond-> {:available? (not (str/blank? html))}
      title  (assoc :title title)
      author (assoc :author author))))
