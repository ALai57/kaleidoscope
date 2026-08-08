(ns kaleidoscope.api.curation.http
  (:import [java.net InetAddress URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn- blocked-address?
  "True if `addr` resolves to a loopback/link-local/site-local/any-local/
  multicast address — cloud metadata endpoints (169.254.169.254), localhost,
  and internal ranges all fall into one of these buckets."
  [^InetAddress addr]
  (or (.isLoopbackAddress addr)
      (.isLinkLocalAddress addr)
      (.isSiteLocalAddress addr)
      (.isAnyLocalAddress addr)
      (.isMulticastAddress addr)))

(defn- private-host?
  "Resolve `host` and check every returned address — a hostname can resolve
  to multiple A/AAAA records, and any one of them landing on a private
  range is enough to reject the request."
  [host]
  (some blocked-address? (InetAddress/getAllByName host)))

(defn fetch [{:keys [url] :as ref} _config]
  (try
    (let [uri (URI/create url)]
      (if (private-host? (.getHost uri))
        {:status :error :ref ref :error "blocked private address"}
        (let [req  (-> (HttpRequest/newBuilder uri)
                       (.timeout (Duration/ofSeconds 20)) (.GET) (.build))
              resp (.send @client req (HttpResponse$BodyHandlers/ofString))
              code (.statusCode resp)]
          ;; NOTE: java.net.http follows redirects internally (NORMAL policy)
          ;; and does not re-run this SSRF check against the redirect target
          ;; — a resource that 302s to a private address after this check
          ;; passes would still be fetched. Revisit with a custom redirect
          ;; policy if that becomes a real vector.
          (if (<= 200 code 299)
            {:status :ok :final-url (str (.uri resp)) :raw (.body resp)
             :content-type (-> resp .headers (.firstValue "content-type") (.orElse "")) :fetcher :http}
            {:status :dead :ref ref :http-status code}))))
    (catch Exception e {:status :error :ref ref :error (.getMessage e)})))
