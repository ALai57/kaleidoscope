(ns kaleidoscope.clients.route53
  (:require [amazonica.aws.route53domains :as r53d]
            [amazonica.aws.simpleemail :as ses]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [selmer.parser :as selmer]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]))

(def template
  (slurp (io/resource "email-templates/intent-to-purchase-domain.html")))

(defn check-domain-availability
  "A simple wrapper around amazonica that handles aws exceptions"
  [domain]
  (try
    (r53d/check-domain-availability {:domain-name domain})
    (catch com.amazonaws.services.route53domains.model.UnsupportedTLDException e
      {:availability "UNSUPPORTED_TLD"})))

(defn check-availability
  [domain]
  (log/infof "Checking availability for domain `%s`" domain)
  (let [parts                  (str/split domain #"\.")
        tld                    (last parts)
        {:keys [availability]} (check-domain-availability domain)]
    (case availability
      "UNAVAILABLE" (do (log/infof "Domain is unavailable")
                        {:domain    domain
                         :available false})

      ;; :prices is normally a vector, but since we're requesting for a specific TLD
      ;; we always expect one and only one element
      "AVAILABLE" (let [prices (update (r53d/list-prices {:tld tld})
                                       :prices first)]

                    ;; TODO: Send confirmation to purchaser
                    (merge {:domain    domain
                            :available true}
                           prices))

      "UNSUPPORTED_TLD"      {:domain domain
                              :error  "UNSUPPORTED_TLD"}
      "INVALID_NAME_FOR_TLD" {:domain domain
                              :error  "INVALID_NAME_FOR_TLD"}
      "PENDING"              {:domain domain
                              :error  "PENDING"})))

(defn receive-order!
  [{:keys [domain price name email] :as inputs}]
  (let [email-text (selmer/render template inputs)]
    (ses/send-email {:destination        {:to-addresses ["andrew.s.lai5@gmail.com"]}
                     :reply-to-addresses ["andrew.s.lai5@gmail.com"]
                     :source             "andrew@andrewslai.com"
                     :message            {:subject (format "Intent to purchase %s" domain)
                                          :body    {:html email-text}}}))
  )

(comment
  (r53d/check-domain-availability {:domain-name "andrewslai.net.com"})
  (r53d/check-domain-availability {:domain-name "andrewslai.com"})
  (r53d/check-domain-availability {:domain-name "andrewslai.ai"})
  ;; => {:availability "UNAVAILABLE"}

  (check-domain-availability "andrewslai.ai")

  (r53d/list-prices {:tld "com"})
  (r53d/list-prices {:tld "ai"})

  (check-availability "andrewfff.com")
  ;; => {:domain "andrewfff.com",
  ;;     :available true,
  ;;     :prices
  ;;     [{:transfer-price {:price 14.0, :currency "USD"},
  ;;       :renewal-price {:price 14.0, :currency "USD"},
  ;;       :change-ownership-price {:price 0.0, :currency "USD"},
  ;;       :restoration-price {:price 57.0, :currency "USD"},
  ;;       :registration-price {:price 14.0, :currency "USD"},
  ;;       :name "com"}]}

  (ses/send-email {:destination        {:to-addresses ["andrew.s.lai5@gmail.com"]}
                   :reply-to-addresses ["andrew.s.lai5@gmail.com"]
                   :source             "andrew@andrewslai.com"
                   :message            {:subject "Intent to purchase domain"
                                        :body    {:html
                                                  ""}}})

  (selmer/render template {:name   "andrew"
                           :domain "myexample.com"
                           :price  "$10.00"})

  (receive-order! {:name   "Andrew Lai"
                   :domain "example.com"
                   :price  "$10.99"
                   :email  "andrew.s.lai5@gmail.com"})

  )
