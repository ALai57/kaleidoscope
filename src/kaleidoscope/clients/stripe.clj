(ns kaleidoscope.clients.stripe
  (:import (com.stripe StripeClient)
           (com.stripe.model PaymentIntent)
           (com.stripe.param PaymentIntentCreateParams)))

(defn payment-intent
  "Returns a client secret - which must be passed to the Frontend to initiate a payment"
  [{:keys [amount currency]}]
  (let [params (.build (doto (PaymentIntentCreateParams/builder)
                         (.setAmount (long amount))
                         (.setCurrency currency)))
        pi (PaymentIntent/create params)]
    {:client-secret (.getClientSecret pi)}))

(comment
  ;; If prototyping, remember to initialize stripe!
  (import (com.stripe Stripe))

  (if-let [api-key (System/getenv "KALEIDOSCOPE_STRIPE_API_KEY")]
    (do (set! Stripe/apiKey api-key)
        (println "Found stripe API key - initializing"))
    (println "Could not find stripe API key. Is `STRIPE_API_KEY` environment variable set?"))

  (payment-intent {})
  Stripe/apiKey

  (defn get-methods
    [kls]
    (->> kls
         .getMethods
         (map #(.getName %))
         sort))

  (get-methods Stripe)
  (get-methods PaymentIntentCreateParams)
  (get-methods PaymentIntent)
  )
