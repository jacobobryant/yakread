#!/usr/bin/env bash
set -x
set -e

BIFF_PROFILE=${1:-prod}
CLJ_VERSION=1.11.1.1165
TRENCH_VERSION=0.4.0
if [ $(uname -m) = "aarch64" ]; then
  ARCH=arm64
else
  ARCH=amd64
fi
TRENCH_FILE=trenchman_${TRENCH_VERSION}_linux_${ARCH}.tar.gz

# Dependencies
echo Running \`apt-get update\`. If this fails, you may need to way a few seconds for a background \
     \`apt\` \ command to finish.
apt-get update
apt-get upgrade
apt-get -y install default-jre rlwrap ufw git snapd
bash < <(curl -s https://download.clojure.org/install/linux-install-$CLJ_VERSION.sh)
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
curl -sSLf https://github.com/athos/trenchman/releases/download/v$TRENCH_VERSION/$TRENCH_FILE | tar zxvfC - /usr/local/bin trench

# Non-root user
useradd -m app
mkdir -m 700 -p /home/app/.ssh
cp /root/.ssh/authorized_keys /home/app/.ssh
chown -R app:app /home/app/.ssh

# Git deploys - only used if you don't have rsync on your machine
set_up_app () {
  cd
  mkdir repo.git
  cd repo.git
  git init --bare
  cat > hooks/post-receive << EOD
#!/usr/bin/env bash
git --work-tree=/home/app --git-dir=/home/app/repo.git checkout -f
EOD
  chmod +x hooks/post-receive
}
sudo -u app bash -c "$(declare -f set_up_app); set_up_app"

make_service() {
  name="$1"
  file="/etc/systemd/system/$name.service"
  cat > "$file" << EOD
[Unit]
Description=$name
StartLimitIntervalSec=500
StartLimitBurst=5

[Service]
Restart=on-failure
RestartSec=5s
EOD
  cat >> "$file"
  cat >> "$file" << EOD

[Install]
WantedBy=multi-user.target
EOD
  systemctl enable "$name"
}

make_service app << EOD
User=app
Environment="BIFF_PROFILE=$BIFF_PROFILE"
WorkingDirectory=/home/app
ExecStart=/bin/sh -c "mkdir -p target/resources; clj -M:prod"
EOD

make_service route-smtp << EOD
ExecStart=iptables -A PREROUTING -t nat -p tcp --dport 25 -j REDIRECT --to-port 2525
EOD

cat > /etc/systemd/journald.conf << EOD
[Journal]
Storage=persistent
EOD
systemctl restart systemd-journald

cat > /etc/sudoers.d/restart-app << EOD
app ALL= NOPASSWD: /bin/systemctl reset-failed app.service
app ALL= NOPASSWD: /bin/systemctl restart app
app ALL= NOPASSWD: /usr/bin/systemctl reset-failed app.service
app ALL= NOPASSWD: /usr/bin/systemctl restart app
EOD
chmod 440 /etc/sudoers.d/restart-app

# Firewall
ufw allow OpenSSH
ufw allow http
ufw allow https
ufw --force enable

# Web server
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg
chmod o+r /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install caddy
read -p "Enter your app's domain name (e.g. example.com): " BIFF_DOMAIN
cat > /etc/caddy/Caddyfile << EOD
$BIFF_DOMAIN {
    encode gzip
    reverse_proxy localhost:8080
}
EOD
systemctl reload caddy
ufw allow "Caddy Full"

# App dependencies
curl -fsSL https://deb.nodesource.com/setup_lts.x | bash -
apt-get -y install nodejs zip
ufw allow smtp
ufw allow 2525

# fail2ban:
# apt install fail2ban
# cp /etc/fail2ban/{jail.conf,jail.local}
# add enabled = true to [nginx-botsearch] section
# in /etc/fail2ban/filter.d/nginx-botsearch.conf, add this line to failregex:
#
#   .*client: <HOST>,.*
#
# systemctl enable fail2ban; systemctl start fail2ban

systemctl start app
systemctl start route-smtp

echo Remember to:
echo - set up fail2ban
echo - install cronjob for sync_dns.py
