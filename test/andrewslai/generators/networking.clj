(ns andrewslai.generators.networking
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]))

(def gen-protocol
  (s/gen :network/protocol))

;; According to Mozilla, labels/components follow the TLD and valid strings are
;;  between 1 and 63 chars in length, including chars [A-Za-z0-9-]
;; https://developer.mozilla.org/en-US/docs/Learn/Common_questions/What_is_a_domain_name
(def gen-domain-char
  (gen/fmap char
            (gen/one-of [(gen/choose 48 57)
                         (gen/choose 65 90)
                         (gen/choose 97 122)
                         (gen/return 45)])))

;; Labels cannot begin or end with `-`
(defn valid-domain?
  [s]
  (and (not (string/ends-with? s "-"))
       (not (string/starts-with? s "-"))))

(defn append
  [s c]
  (str s c))

(defn prepend
  [s c]
  (str c s))

(def gen-domain
  (gen/fmap (fn [xs]
              (let [s (apply str xs)]
                (cond-> s
                  (string/starts-with? s "-") (prepend (gen/generate gen/char-alphanumeric))
                  (string/ends-with? s "-")   (append (gen/generate gen/char-alphanumeric)))))
            (gen/vector gen-domain-char 1 10)))

(def gen-host
  (gen/fmap (fn [xs] (string/join "." xs))
            (gen/vector gen-domain 1 10)))

(def gen-port
  (s/gen :network/port))

(def gen-delims
  (gen/elements [":" "/" "?" "#" "[" "]" "@"]))

(def gen-sub-delims
  (gen/elements ["!" "$" "&" "'" "(" ")" "*" "+" "," ";" "="]))

(def gen-reserved-char
  (gen/one-of [gen-delims gen-sub-delims]))

(def gen-endpoint
  (gen/fmap (fn [xs] (str "/" (string/join "/" xs)))
            (gen/vector gen/string)))

(def gen-url
  (gen/fmap (fn [{:keys [protocol host port endpoint]}]
              (format "%s://%s:%s%s" protocol host port endpoint))
            (gen/hash-map :protocol gen-protocol
                          :app     gen-host
                          :port     gen-port
                          :endpoint gen-endpoint)))

(def gen-request
  (gen/fmap (fn [[protocol host endpoint]]
              {:scheme protocol
               :headers {"host" host}
               :uri endpoint})
            (gen/tuple gen-protocol gen-host gen-endpoint)))
