(defproject autho "0.1.0-SNAPSHOT"
  :description "XACML-based Authorization Server with Policy Decision Point (PDP) and Policy Administration Point (PAP) capabilities"
  :url "https://github.com/rsclison/autho"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :plugins [[gorillalabs/lein-docker "1.5.0"]]
  :docker {:image-name "autho-pdp"}
  :managed-dependencies [[io.netty/netty-common "4.1.72.Final"]
                         [io.netty/netty-buffer "4.1.72.Final"]
                         [io.netty/netty-transport "4.1.72.Final"]
                         [io.netty/netty-handler "4.1.72.Final"]
                         [io.netty/netty-codec "4.1.72.Final"]
                         [io.netty/netty-codec-http "4.1.72.Final"]
                         [io.netty/netty-codec-http2 "4.1.72.Final"]
                         [io.netty/netty-resolver "4.1.72.Final"]
                         [io.netty/netty-all "4.1.72.Final"]]
  :dependencies [[org.clojure/clojure "1.12.3"]
                 [org.clojure/data.csv "1.1.0"]
                 [compojure "1.7.2"]
                 [buddy/buddy-auth "3.0.323"]
                 [com.appsflyer/donkey "0.5.2" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [luposlip/json-schema "0.4.7" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [com.fasterxml.jackson.core/jackson-core "2.18.2"]
                 [com.fasterxml.jackson.core/jackson-databind "2.18.2"]
                 [com.fasterxml.jackson.core/jackson-annotations "2.18.2"]
                 [org.clojure/data.json "2.5.1"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [clojure.java-time "1.4.3"]
                 [org.clojure/core.cache "1.1.234"]
                 [clj-http "3.13.1"]
                 [puppetlabs/clj-ldap "0.4.1"]
                 [hiccup "2.0.0"]
                 [metosin/jsonista "0.3.13"]
                 [com.h2database/h2 "2.4.240"]
                 [com.brunobonacci/mulog "0.9.0"]
                 [com.datomic/datomic-free "0.9.5697" :exclusions [org.slf4j/slf4j-nop]]
                 [org.slf4j/slf4j-api "2.0.17"]
                 [ch.qos.logback/logback-classic "1.5.18"]
                 [org.apache.kafka/kafka-clients "4.1.0"]
                 [org.rocksdb/rocksdbjni "10.4.2"]]
  :main autho.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
