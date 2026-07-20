(ns kaleidoscope.workflows.llm-executor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.workflows.llm-executor :as llm-executor]
            [taoensso.timbre :as log])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(deftest anthropic-http-client-has-a-connect-timeout-test
  (testing "A stalled TCP connect to Anthropic can't hang the calling thread forever"
    (is (= (java.time.Duration/ofSeconds 10)
           (.orElse (.connectTimeout @@#'llm-executor/anthropic-http-client) nil)))))

(defn- with-stub-anthropic
  "Run `f` against a local HTTP server that answers every request with
  `status`/`body`, with the Anthropic endpoint pointed at it."
  [status body f]
  (let [server  (HttpServer/create (InetSocketAddress. 0) 0)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (let [bytes (.getBytes ^String body "UTF-8")]
                      (.sendResponseHeaders exchange status (alength bytes))
                      (with-open [os (.getResponseBody exchange)]
                        (.write os bytes)))))]
    (.createContext server "/v1/messages" handler)
    (.start server)
    (try
      (let [port (.getPort (.getAddress server))
            url  (str "http://localhost:" port "/v1/messages")]
        (with-redefs [llm-executor/anthropic-messages-url url]
          (f)))
      (finally (.stop server 0)))))

(deftest discover-streaming-surfaces-non-200-test
  (testing "A non-200 from Anthropic during a streaming (Discover) step throws
            instead of silently returning empty text — a bad API key must not
            look like a successful, empty discovery"
    (log/with-min-level :fatal
      (with-stub-anthropic
        401 "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"API key is invalid.\"}}"
        (fn []
          (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                (#'llm-executor/stream-step-to-output!
                                 "bad-key" "system" [{:role "user" :content "hi"}]
                                 (java.io.ByteArrayOutputStream.))))]
            (is (= 401 (:status (ex-data ex)))
                "the HTTP status is preserved on the thrown exception")))))))
