(ns kaleidoscope.api.curation.mock
  (:require [clojure.string :as str]))

(defn fetch [{:keys [url] :as ref} _config]
  (cond
    (str/includes? (or url "") "/dead/")  {:status :dead :ref ref}
    :else {:status :ok :final-url url :raw "<html>mock</html>"
           :content-type "text/html" :fetcher :mock}))

(defn vet [content _taste rubric]
  ;; deterministic: title starting "WRONG" fails identity; "NOISE" scores low
  (let [t (:title content "")
        base (cond (str/starts-with? t "WRONG") {:matches? false :scores {} :composite 0.0 :reason "different resource"}
                   (str/starts-with? t "NOISE") {:matches? true :scores {:relevance 2 :depth 2 :taste-fit 2 :novelty 2} :reason "tangential"}
                   :else {:matches? true :scores {:relevance 8 :depth 8 :taste-fit 8 :novelty 6}})]
    (assoc base :composite (or (:composite base) 0.0) :est-time "15 min")))

(defn discover [taste _config]
  ;; three good, one wrong-target, one noise, one dead link
  [{:kind "article" :title "Deep Work primer" :source "calnewport.com" :ref {:url "https://example.com/ok/1"} :why "on-taste"}
   {:kind "book"    :title "Solitude"        :source "standardebooks.org" :ref {:url "https://example.com/ok/2"} :why "on-taste"}
   {:kind "paper"   :title "Attention"       :source "fs.blog" :ref {:url "https://example.com/ok/3"} :why "on-taste"}
   {:kind "article" :title "WRONG target"    :source "example.com" :ref {:url "https://example.com/ok/4"} :why "looks right"}
   {:kind "article" :title "NOISE roundup"   :source "noise.example" :ref {:url "https://example.com/ok/5"} :why "listicle"}
   {:kind "article" :title "Vanished post"   :source "example.com" :ref {:url "https://example.com/dead/1"} :why "looks right"}])
