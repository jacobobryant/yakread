#!/usr/bin/python3
# run `crontab -e` and append this line:
# */5 * * * * /home/app/bin/sync_dns.py
import requests
import sys

def read_config(config_path):
    config = {}
    try:
        with open(config_path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if '=' in line:
                    key, val = line.split('=', 1)
                    config[key.strip()] = val.strip()
    except Exception as e:
        print(f"Error reading config file '{config_path}': {e}")
        sys.exit(1)
    return config

config = read_config('/home/app/config.env')

DIGITALOCEAN_TOKEN = config.get("DO_TOKEN")
DOMAIN = config.get("DOMAIN")
IPV4_URL = "http://ifconfig.me"
DO_API_BASE = "https://api.digitalocean.com/v2"

def get_dns_a_record():
    url = f"{DO_API_BASE}/domains/{DOMAIN}/records"
    headers = {"Authorization": f"Bearer {DIGITALOCEAN_TOKEN}"}
    resp = requests.get(url, headers=headers)
    resp.raise_for_status()
    records = resp.json()["domain_records"]
    for rec in records:
        if rec["type"] == "A" and rec["name"] == "@":
            return rec

def update_dns_a_record(record_id, new_ip):
    url = f"{DO_API_BASE}/domains/{DOMAIN}/records/{record_id}"
    headers = {"Authorization": f"Bearer {DIGITALOCEAN_TOKEN}"}
    data = {"data": new_ip}
    resp = requests.put(url, json=data, headers=headers)
    resp.raise_for_status()
    return resp.json()

def main():
    if not DIGITALOCEAN_TOKEN:
        print("Error: Set your DigitalOcean API token in the DO_TOKEN environment variable.")
        sys.exit(1)

    public_ip = requests.get(IPV4_URL).text.strip()
    record = get_dns_a_record()
    if not record:
        print(f"Error: No A record found for @.{DOMAIN}")
        sys.exit(1)
    current_ip = record["data"]

    if public_ip == current_ip:
        print(f"IP unchanged ({public_ip}), nothing to do.")
    else:
        print(f"Updating @.{DOMAIN} from {current_ip} to {public_ip}")
        print(repr(update_dns_a_record(record["id"], public_ip)))

if __name__ == "__main__":
    main()
