(ns kaleidoscope.api.curation.parse.youtube
  (:require [clojure.string :as str]))

(defn- extract-title [html]
  (when-let [[_ title] (re-find #"(?is)<title[^>]*>(.*?)</title>" html)]
    (-> title str/trim not-empty)))

(defn- clean-title
  "YouTube page titles are rendered as \"<video title> - YouTube\"; strip the suffix."
  [title]
  (some-> title (str/replace #"(?i)\s*-\s*YouTube\s*$" "") str/trim not-empty))

(defn- unavailable?
  "No <title> at all, or the page body signals the video was removed/blocked."
  [html raw-title]
  (or (str/blank? raw-title)
      (boolean (re-find #"(?i)video (is )?unavailable|this video isn.t available|video removed|has been removed" html))))

(defn parse-content
  "Thin oEmbed-free YouTube parse: pulls the page <title> (minus the \" - YouTube\"
  suffix) as the video title, and flags :available? false when the page signals a
  missing/removed/blocked video."
  [{:keys [raw]}]
  (let [html      (or raw "")
        raw-title (extract-title html)
        title     (clean-title raw-title)]
    (cond-> {:available? (not (unavailable? html raw-title))}
      title (assoc :title title))))
