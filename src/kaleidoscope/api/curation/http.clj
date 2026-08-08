(ns kaleidoscope.api.curation.http
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn fetch [{:keys [url] :as ref} _config]
  (try
    (let [req  (-> (HttpRequest/newBuilder (URI/create url))
                   (.timeout (Duration/ofSeconds 20)) (.GET) (.build))
          resp (.send @client req (HttpResponse$BodyHandlers/ofString))
          code (.statusCode resp)]
      (if (<= 200 code 299)
        {:status :ok :final-url (str (.uri resp)) :raw (.body resp)
         :content-type (-> resp .headers (.firstValue "content-type") (.orElse "")) :fetcher :http}
        {:status :dead :ref ref :http-status code}))
    (catch Exception e {:status :error :ref ref :error (.getMessage e)})))
