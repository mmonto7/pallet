(ns pallet.crate.etc-hosts
  "/etc/hosts file."
  (:require
   [clojure.string :as string]
   [pallet.node :as node]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils])
  (:use
   [clojure.tools.logging :only [debugf]]
   [pallet.actions :only [exec-checked-script remote-file sed]]
   [pallet.compute :only [os-hierarchy]]
   [pallet.crate
    :only [defplan get-settings update-settings
           nodes-in-group nodes-with-role
           target-name os-family target-name defmulti-plan defmethod-plan]]))

(defn- format-entry
  [entry]
  (format "%s %s"  (key entry) (name (val entry))))

(defplan host
  "Declare a host entry. Names should be a sting containing one or more names
  for the address"
  [address names]
  (update-settings :hosts merge {address names}))

(defplan hosts-for-group
  "Declare host entries for all nodes of a group"
  [group-name & {:keys [private-ip]}]
  (let [ip (if private-ip node/private-ip node/primary-ip)
        group-nodes (nodes-in-group group-name)]
    (update-settings
     :hosts merge
     (into {} (map #(vector (ip %) (node/hostname %)) group-nodes)))))

(defplan hosts-for-role
  "Declare host entries for all nodes of a role"
  [role & {:keys [private-ip]}]
  (let [ip (if private-ip node/private-ip node/primary-ip)
        nodes (nodes-with-role role)]
    (update-settings
     :hosts merge (into {}
                        (map
                         #(vector (ip %) (node/hostname %))
                         (map :node nodes))))))

(defn ^{:private true} localhost
  ([node-name]
     {"127.0.0.1" (str "localhost localhost.localdomain " node-name)})
  ([]
     {"127.0.0.1" (str "localhost localhost.localdomain")}))

(defn- format-hosts*
  [entries]
  (string/join "\n" (map format-entry entries)))

(defplan format-hosts
  []
  (let [settings (get-settings :hosts)
        node-name (target-name)]
    (format-hosts*
     (merge
      settings
      (if (some #(= node-name %) (vals settings))
        (localhost)
        (localhost node-name))))))

(defplan hosts
  "Writes the hosts files"
  []
  (let [content (format-hosts)]
    (remote-file
     (stevedore/script (~lib/etc-hosts))
     :owner "root:root"
     :mode 644
     :content content)))

(defmulti-plan set-hostname*
  (fn [hostname]
    (debugf "hostname dispatch %s" hostname)
    (let [os (os-family)]
      (debugf "hostname for os %s" os)
      os))
  :hierarchy #'os-hierarchy)

(defmethod-plan set-hostname* :linux [hostname]
  ;; change the hostname now
  (exec-checked-script
   "Set hostname"
   ("hostname " ~hostname))
  ;; make sure this change will survive reboots
  (remote-file
   "/etc/hostname"
   :owner "root" :group "root" :mode "0644"
   :content hostname))

(defmethod-plan set-hostname* :rh-base [hostname]
  ;; change the hostname now
  (exec-checked-script "Set hostname" ("hostname " ~hostname))
  ;; make sure this change will survive reboots
  (sed "/etc/sysconfig/network"
       {"HOSTNAME=.*" (str "HOSTNAME=" hostname)}))

(defplan set-hostname
  "Set the hostname on a node. Note that sudo may stop working if the
hostname is not in /etc/hosts."
  []
  (let [node-name (target-name)]
    (sed (stevedore/script (~lib/etc-hosts))
         {"127\\.0\\.0\\.1\\(.*\\)" (str "127.0.0.1\\1 " node-name)}
         :restriction (str "/" node-name "/ !")
         :quote-with "'")
    (set-hostname* node-name)))
