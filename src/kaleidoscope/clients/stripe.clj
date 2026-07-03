(ns kaleidoscope.clients.stripe
  (:import (com.stripe StripeClient)
           (com.stripe.model PaymentIntent)
           (com.stripe.net RequestOptions)
           (com.stripe.param PaymentIntentCreateParams)))

(defn payment-intent
  "Returns a client secret - which must be passed to the Frontend to initiate
  a payment. `amount`/`currency` are expected to already be validated by the
  caller (see kaleidoscope.http-api.kaleidoscope/PaymentRequest) — this
  function performs no bounds checking of its own.

  Always passes an idempotency key so retries (client-supplied or, failing
  that, a random fallback) can't multiply live PaymentIntent objects in
  Stripe."
  [{:keys [amount currency idempotency-key]}]
  (let [params  (.build (doto (PaymentIntentCreateParams/builder)
                          (.setAmount (long amount))
                          (.setCurrency currency)))
        options (.build (doto (RequestOptions/builder)
                          (.setIdempotencyKey (or idempotency-key (str (java.util.UUID/randomUUID))))))
        pi      (PaymentIntent/create params options)]
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
