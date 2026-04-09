(ns kaleidoscope.clients.route53
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [selmer.parser :as selmer]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]))

(def template
  (slurp (io/resource "email-templates/intent-to-purchase-domain.html")))

(defonce ^:private r53d-client
  (delay (aws/client {:api :route53domains})))

(defonce ^:private ses-client
  (delay (aws/client {:api :email})))

(defn check-domain-availability
  "Returns {:Availability \"AVAILABLE\"} etc., or {:Availability \"UNSUPPORTED_TLD\"}
  when the TLD is not supported (aws-api returns an anomaly in that case)."
  [domain]
  (let [result (aws/invoke @r53d-client {:op      :CheckDomainAvailability
                                         :request {:DomainName domain}})]
    (if (:cognitect.anomalies/category result)
      {:Availability "UNSUPPORTED_TLD"}
      result)))

(defn check-availability
  [domain]
  (log/infof "Checking availability for domain `%s`" domain)
  (let [parts                  (str/split domain #"\.")
        tld                    (last parts)
        {:keys [Availability]} (check-domain-availability domain)]
    (case Availability
      "UNAVAILABLE" (do (log/infof "Domain is unavailable")
                        {:domain    domain
                         :available false})

      ;; :Prices is normally a vector; since we request a specific TLD we always
      ;; expect exactly one element.
      "AVAILABLE" (let [prices (update (aws/invoke @r53d-client {:op      :ListPrices
                                                                  :request {:Tld tld}})
                                       :Prices first)]
                    (merge {:domain    domain
                            :available true}
                           prices))

      "UNSUPPORTED_TLD"      {:domain domain :error "UNSUPPORTED_TLD"}
      "INVALID_NAME_FOR_TLD" {:domain domain :error "INVALID_NAME_FOR_TLD"}
      "PENDING"              {:domain domain :error "PENDING"})))

(defn receive-order!
  [{:keys [domain price name email] :as inputs}]
  (let [email-text (selmer/render template inputs)]
    (aws/invoke @ses-client
                {:op      :SendEmail
                 :request {:FromEmailAddress "andrew@andrewslai.com"
                           :ReplyToAddresses ["andrew.s.lai5@gmail.com"]
                           :Destination      {:ToAddresses ["andrew.s.lai5@gmail.com"]}
                           :Content          {:Simple {:Subject {:Data    (format "Intent to purchase %s" domain)
                                                                 :Charset "UTF-8"}
                                                       :Body    {:Html {:Data    email-text
                                                                        :Charset "UTF-8"}}}}}})))

(comment
  (aws/invoke @r53d-client {:op :CheckDomainAvailability :request {:DomainName "andrewslai.net.com"}})
  (aws/invoke @r53d-client {:op :CheckDomainAvailability :request {:DomainName "andrewslai.com"}})
  (aws/invoke @r53d-client {:op :CheckDomainAvailability :request {:DomainName "andrewslai.ai"}})

  (check-domain-availability "andrewslai.ai")

  (aws/invoke @r53d-client {:op :ListPrices :request {:Tld "com"}})
  (aws/invoke @r53d-client {:op :ListPrices :request {:Tld "ai"}})

  (check-availability "andrewfff.com")

  (receive-order! {:name   "Andrew Lai"
                   :domain "example.com"
                   :price  "$10.99"
                   :email  "andrew.s.lai5@gmail.com"})
  )
