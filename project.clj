(def COMPILE-TIME-LOG-LEVEL
  :error)

(defproject org.clojars.alai57/andrewslai "0.0.53-SNAPSHOT"
  :url "https://github.com/ALai57/andrewslai"
  :license {:name         "Eclipse Public License - v 1.0"
            :url          "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments     "same as Clojure"}
  :description "The backend for alai57's blogging app"
  :dependencies [[aleph "0.4.7-alpha7"]
                 [amazonica "0.3.156" :exclusions [org.apache.commons/commons-lang3
                                                   commons-logging
                                                   com.taoensso/encore
                                                   org.apache.httpcomponents/httpclient
                                                   com.amazonaws/aws-java-sdk]]
                 [biiwide/sandboxica "0.3.0" :scope "test"]
                 [buddy/buddy-auth "2.2.0" :exclusions [com.google.code.gson/gson
                                                        org.clojure/clojurescript]]
                 [cheshire "5.10.0"]
                 [clj-http "3.10.0" :exclusions [commons-logging]]
                 [com.amazonaws/aws-java-sdk-s3 "1.11.850" :exclusions [commons-logging
                                                                        org.apache.httpcomponents/httpclient]]
                 [com.twelvemonkeys.imageio/imageio-batik "3.7.0"]
                 [com.twelvemonkeys.servlet/servlet "3.7.0"]
                 [com.taoensso/timbre "5.1.2"]
                 [honeysql "0.9.10"]
                 [io.zonky.test/embedded-postgres "1.2.6" :scope "test"]
                 [javax.servlet/servlet-api "2.5"]
                 [metosin/compojure-api "2.0.0-alpha31" :exclusions [ring/ring-codec joda-time]]
                 [metosin/spec-tools "0.10.3"]
                 [migratus "1.2.8" :scope "test"]
                 [nubank/matcher-combinators "3.1.4" :exclusions [io.aviso/pretty
                                                                  joda-time] :scope "test"]
                 [org.apache.xmlgraphics/batik-transcoder "1.14"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.0.567" :exclusions [org.clojure/tools.reader]]
                 [org.clojure/test.check "0.10.0"]
                 [org.clojure/data.codec "0.1.1"]
                 [org.clojure/data.csv "1.0.0" :scope "dev"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [org.keycloak/keycloak-adapter-core "12.0.3"]
                 [org.keycloak/keycloak-adapter-spi "12.0.3"]
                 [org.keycloak/keycloak-admin-client "12.0.3"]
                 [org.keycloak/keycloak-common "12.0.3"]
                 [org.keycloak/keycloak-core "12.0.3" :exclusions [com.fasterxml.jackson.core/jackson-annotations
                                                                   com.fasterxml.jackson.core/jackson-core]]

                 ;; Intercept logging to Apache Commons Logging (introduced by AWS SDK)
                 [com.fzakaria/slf4j-timbre "0.3.21"]
                 ;;[org.slf4j/slf4j-nop "1.7.30"] ;; For a no-op logger

                 ;; Bridges between different logging libs and SLF4J
                 [org.slf4j/log4j-over-slf4j "1.7.30"]

                 [com.h2database/h2 "1.4.200"]
                 [org.postgresql/postgresql "42.2.11"]
                 [peridot "0.5.3" :scope "test"]
                 [ring "1.8.0" :exclusions [ring/ring-codec org.clojure/java.classpath ring/ring-jetty-adapter]]
                 [ring/ring-json "0.5.0" :exclusions [joda-time]]
                 [ring/ring-mock "0.4.0" :scope "test"]
                 [seancorfield/next.jdbc "1.1.613"]
                 [slingshot "0.12.2"]]

  :plugins [[lein-shell "0.5.0"]]

  :repositories [["releases" {:url   "https://repo.clojars.org"
                              :creds :gpg}]]

  :jvm-opts [;;~(format "-Dtaoensso.timbre.min-level.edn=%s" COMPILE-TIME-LOG-LEVEL)
             ~(format "-DTIMBRE_LEVEL=%s" COMPILE-TIME-LOG-LEVEL)]

  ;; Used to make this compatible with Java 11
  :managed-dependencies [[metosin/ring-swagger-ui "3.25.3"]
                         [org.clojure/core.rrb-vector "0.1.1"]
                         [org.flatland/ordered "1.5.7"]
                         [io.zonky.test.postgres/embedded-postgres-binaries-darwin-amd64 "10.6.0" :scope "test"]
                         [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64-alpine "10.6.0" :scope "test"]
                         [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64 "10.6.0" :scope "test"]]

  :aot :all

  :uberjar-name "andrewslai.jar"
  :uberjar-exclusions [#"public/.*" #".txz"]

  :main andrewslai.clj.main

  ;; Speeds up Docker builds, see https://docs.docker.com/develop/develop-images/build_enhancements/
  :shell {:env {"DOCKER_BUILDKIT" "1"}}

  :profiles {:dev {:plugins [[lein-ancient "0.6.15"]
                             [lein-kibit "0.1.8"]
                             [lein-ring "0.12.5"]]
                   :aliases {"migratus" ["run" "-m" andrewslai.clj.persistence.migrations]}}}

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["clean"]
                  ["uberjar"]
                  ["deploy" "clojars"]
                  ["shell" "docker" "build" "-t" "andrewslai" "."]
                  ["shell" "docker" "tag" "andrewslai:latest" "758589815425.dkr.ecr.us-east-1.amazonaws.com/andrewslai_ecr"]
                  ["shell" "docker" "push" "758589815425.dkr.ecr.us-east-1.amazonaws.com/andrewslai_ecr"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]])
