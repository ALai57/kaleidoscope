(def COMPILE-TIME-LOG-LEVEL
  :debug)

(defproject org.clojars.alai57/kaleidoscope "0.1.7-SNAPSHOT"
  :url "https://github.com/ALai57/kaleidoscope"
  :license {:name         "Eclipse Public License - v 1.0"
            :url          "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments     "same as Clojure"}
  :description "A blogging platform/CMS"
  :dependencies
  [[amazonica "0.3.162"]
   [bk/ring-gzip "0.3.0"]
   [buddy/buddy-auth "3.0.323"]
   [camel-snake-kebab "0.4.3"]
   [cheshire "5.11.0"]
   [clj-http "3.12.3"]
   [com.amazonaws/aws-java-sdk-s3 "1.12.385"]
   [com.fzakaria/slf4j-timbre "0.3.21"] ;; Intercept logging to Apache Commons Logging (introduced by AWS SDK)
   [com.github.seancorfield/next.jdbc "1.3.847"]
   [com.github.steffan-westcott/clj-otel-api "0.1.5"]
   [com.github.steffan-westcott/clj-otel-sdk "0.1.5"]
   [com.github.steffan-westcott/clj-otel-exporter-otlp "0.1.5"]
   [com.github.steffan-westcott/clj-otel-sdk-extension-resources "0.1.5"]

   [io.opentelemetry/opentelemetry-sdk-extension-autoconfigure "1.17.0-alpha"]
   [io.opentelemetry/opentelemetry-sdk-extension-resources     "1.17.0"]
   [io.opentelemetry/opentelemetry-exporter-otlp               "1.17.0"]
   [io.grpc/grpc-netty-shaded                                  "1.49.0"]
   [io.grpc/grpc-protobuf                                      "1.49.0"]
   [io.grpc/grpc-stub                                          "1.49.0"]

   [io.grpc/grpc-protobuf "1.49.0"]
   [com.h2database/h2 "2.1.214"]
   [com.taoensso/timbre "6.0.4"]
   [com.twelvemonkeys.servlet/servlet "3.9.4"]
   [com.zaxxer/HikariCP "3.3.1"]
   [com.fasterxml.jackson.datatype/jackson-datatype-joda "2.13.3"] ;; For compatibility
   [honeysql "1.0.461"]
   [io.zonky.test/embedded-postgres "1.2.6"]
   [javax.servlet/servlet-api "2.5"]
   [lambdaisland/deep-diff2 "2.7.169"]
   [metosin/compojure-api "2.0.0-alpha31"]
   [metosin/malli "0.10.0"]
   [metosin/spec-tools "0.10.3"]
   [migratus "1.4.9"]
   [org.clojure/clojure "1.11.1"]
   [org.keycloak/keycloak-adapter-core "21.0.1"]
   [org.keycloak/keycloak-adapter-spi "21.0.1"]
   [org.keycloak/keycloak-admin-client "21.0.1"]
   [org.keycloak/keycloak-common "21.0.1"]
   [org.keycloak/keycloak-core "21.0.1"]
   [org.postgresql/postgresql "42.5.1"]
   [org.slf4j/log4j-over-slf4j "1.7.30"] ;; Bridges between different logging libs and SLF4J
   [ring "1.9.6" :exclusions [ring/ring-codec org.clojure/java.classpath]]
   [ring/ring-json "0.5.1"]
   [slingshot "0.12.2"]
   ]

  :plugins [[lein-shell "0.5.0"]]

  :repositories [["releases" {:url   "https://repo.clojars.org"
                              :creds :gpg}]]

  :jvm-opts [;;~(format "-Dtaoensso.timbre.min-level.edn=%s" COMPILE-TIME-LOG-LEVEL)
             ~(format "-DTIMBRE_LEVEL=%s" COMPILE-TIME-LOG-LEVEL)]

  ;; Used to make this compatible with Java 11
  :managed-dependencies [[metosin/ring-swagger-ui "4.5.0"]
                         [org.clojure/core.rrb-vector "0.1.1"]
                         [org.flatland/ordered "1.5.7"]
                         [io.zonky.test.postgres/embedded-postgres-binaries-darwin-amd64 "10.6.0" :scope "test"]
                         [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64-alpine "10.6.0" :scope "test"]
                         [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64 "10.6.0" :scope "test"]]

  :aot :all

  :uberjar-name "kaleidoscope.jar"
  :uberjar-exclusions [#"public/.*" #".txz"]

  :main kaleidoscope.clj.main

  ;; Speeds up Docker builds, see https://docs.docker.com/develop/develop-images/build_enhancements/
  :shell {:env {"DOCKER_BUILDKIT" "1"}}

  :profiles {:dev {:plugins      [[lein-ancient "0.6.15"]
                                  [lein-kibit "0.1.8"]
                                  [lein-ring "0.12.5"]]
                   :dependencies [[biiwide/sandboxica "0.4.0"]
                                  [io.zonky.test/embedded-postgres "1.2.6"]
                                  [ring/ring-mock "0.4.0"]
                                  [nubank/matcher-combinators "3.7.2"]
                                  [org.clojure/tools.cli "1.0.214"]
                                  [org.clojure/tools.namespace "1.3.0"]
                                  ]
                   :aliases      {"migratus" ["run" "-m" kaleidoscope.clj.persistence.rdbms.migrations]
                                  "test"     ["run" "-m" kaleidoscope.clj.test-main]}}}

  :release-tasks [["vcs" "assert-committed"]
                  #_["change" "version" "leiningen.release/bump-version" "release"]
                  #_["vcs" "commit"]
                  ["clean"]
                  #_["deploy" "clojars"]
                  ["uberjar"]

                  ;; Necessary for dockerizing on M1:
                  ;; https://stackoverflow.com/questions/67361936/exec-user-process-caused-exec-format-error-in-aws-fargate-service
                  ["shell" "docker" "buildx" "build" "--platform=linux/amd64" "-t" "kaleidoscope" "."]
                  ["shell" "docker" "tag" "kaleidoscope:latest" "758589815425.dkr.ecr.us-east-1.amazonaws.com/kaleidoscope_ecr"]
                  ["shell" "docker" "push" "758589815425.dkr.ecr.us-east-1.amazonaws.com/kaleidoscope_ecr"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]])
