;; nvd-helper — projet séparé pour l'analyse CVE via nvd-clojure.
;; NE PAS ajouter nvd-clojure comme dépendance du projet principal autho.
;;
;; Usage (depuis la racine du projet autho) :
;;   ./nvd-check.sh
;;
;; Ou manuellement depuis ce répertoire :
;;   NVD_API_TOKEN=<clé> lein with-profile -user run -m nvd.task.check \
;;     nvd-clojure.edn "$(cd ..; lein with-profile -user,-dev classpath)"
(defproject nvd-helper "local"
  :description "nvd-clojure helper — CVE scan for autho"
  :dependencies [[nvd-clojure "5.3.0"]
                 [org.clojure/clojure "1.12.3"]]
  :jvm-opts ["-Dclojure.main.report=stderr"])
