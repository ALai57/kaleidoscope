(defproject org.clojars.alai57/andrewslai "0.0.33-SNAPSHOT"
  :url "https://github.com/ALai57/andrewslai"
  :license {:name         "Eclipse Public License - v 1.0"
            :url          "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments     "same as Clojure"}
  :description "The backend for alai57's blogging app"
  :dependencies [[aleph "0.4.7-alpha7"]
                 [amazonica "0.3.156" :exclusions [com.amazonaws/aws-java-sdk]]
                 [com.amazonaws/aws-java-sdk-s3 "1.11.850"]
                 [buddy/buddy-auth "2.2.0"]
                 [cheshire "5.10.0"]
                 [clj-http "3.10.0"]
                 [org.slf4j/slf4j-nop "1.7.30"]
                 [io.zonky.test/embedded-postgres "1.2.6" :scope "test"]
                 [day8.re-frame/http-fx "0.2.3"]
                 [hickory "0.7.1"]
                 [honeysql "0.9.10"]
                 [metosin/compojure-api "2.0.0-alpha31"]
                 [metosin/spec-tools "0.10.3"]
                 [migratus "1.2.8" :scope "test"]
                 [nubank/matcher-combinators "3.1.4" :scope "test"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.0.567"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/data.csv "1.0.0" :scope "dev"]
                 [org.clojure/data.codec "0.1.1"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [org.keycloak/keycloak-adapter-core "12.0.3"]
                 [org.keycloak/keycloak-adapter-spi "12.0.3"]
                 [org.keycloak/keycloak-admin-client "12.0.3"]
                 [org.keycloak/keycloak-core "12.0.3"]
                 [org.keycloak/keycloak-common "12.0.3"]
                 [org.postgresql/postgresql "42.2.11"]
                 [ring "1.8.0"]
                 [ring/ring-mock "0.4.0" :scope "test"]
                 [ring/ring-json "0.5.0"]
                 [seancorfield/next.jdbc "1.1.613"]
                 [slingshot "0.12.2"]
                 [com.taoensso/timbre "4.10.0"]]

  :plugins [[lein-shell "0.5.0"]]

  :repositories [["releases" {:url   "https://repo.clojars.org"
                              :creds :gpg}]]

  ;; Used to make this compatible with Java 11
  :managed-dependencies
  [[metosin/ring-swagger-ui "3.25.3"]
   [org.clojure/core.rrb-vector "0.1.1"]
   [org.flatland/ordered "1.5.7"]
   [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64-alpine "10.6.0" :scope "test"]
   [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64 "10.6.0" :scope "test"]]

  :aot :all

  :uberjar-name "andrewslai.jar"
  :uberjar-exclusions [#"public/.*"]

  :main andrewslai.clj.handler

  ;; Speeds up Docker builds, see https://docs.docker.com/develop/develop-images/build_enhancements/
  :shell {:env {"DOCKER_BUILDKIT" "1"}}

  :profiles
  {:dev {:plugins [[lein-ancient "0.6.15"]
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
