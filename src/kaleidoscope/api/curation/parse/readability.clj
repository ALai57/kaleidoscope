(ns kaleidoscope.api.curation.parse.readability
  (:require [clojure.string :as str]))

(defn- extract-title [html]
  (when-let [[_ title] (re-find #"(?is)<title[^>]*>(.*?)</title>" html)]
    (-> title str/trim not-empty)))

(defn- strip-tags [html]
  (-> html
      (str/replace #"(?is)<script.*?</script>" " ")
      (str/replace #"(?is)<style.*?</style>" " ")
      (str/replace #"(?is)<!--.*?-->" " ")
      (str/replace #"(?is)<[^>]+>" " ")
      (str/replace #"&nbsp;" " ")
      (str/replace #"&amp;" "&")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"&quot;" "\"")
      (str/replace #"\s+" " ")
      str/trim))

(defn parse-content
  "Default readability-style parse: strips HTML tags down to plain text and pulls
  the document <title>. Used for any host without a dedicated parser."
  [{:keys [raw]}]
  (let [html  (or raw "")
        text  (strip-tags html)
        title (extract-title html)]
    (cond-> {:available? (not (str/blank? text))}
      (not (str/blank? text)) (assoc :text text)
      title                   (assoc :title title))))
