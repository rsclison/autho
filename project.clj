(defproject autho "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :plugins [[gorillalabs/lein-docker "1.5.0"]]
  :docker {:image-name "autho-pdp"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/data.csv "1.0.1"]
                 [compojure "1.7.1"]
                 [com.appsflyer/donkey "0.5.2" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [luposlip/json-schema "0.4.6" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [com.fasterxml.jackson.core/jackson-core "2.17.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.17.0"]
                 [com.fasterxml.jackson.core/jackson-annotations "2.17.0"]
                 [org.clojure/data.json "2.5.1"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [clojure.java-time "1.4.3"]
                 [org.clojure/core.cache "1.1.234"]
                 [clj-http "3.13.0"]
                 [puppetlabs/clj-ldap "0.4.1"]
                 [hiccup "1.0.5"]
                 [clj-json "0.5.3"]
                 [com.h2database/h2 "2.3.232"]
                 [com.brunobonacci/mulog "0.9.0"]
                 [com.datomic/datomic-free "0.9.5697" :exclusions [org.slf4j/slf4j-nop]]
                 [org.slf4j/slf4j-api "1.7.32"]
                 [ch.qos.logback/logback-classic "1.2.6"]]
  :main autho.core
  :target-path "target/%s"
  :profiles {:dev {:plugins [[gorillalabs/lein-docker "1.5.0"]]}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
