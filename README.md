1. Verify Plugin Loaded
Check the server console for:

Code (Text):
[ServerWebAdmin] ServerWebAdmin Enabled!
[ServerWebAdmin] Web Server Started on Port 8080

If you see errors, check plugins/ServerWebAdmin/config.yml exists and is valid.

2. Access the Web UI
Open your browser to: http://<server-ip>:8080

Replace <server-ip> with your server's IP address (use localhost if on the same machine).

3. Log In
Default credentials:
Username: admin
Password: admin123
4. (Optional) Change Default Password
Go to User Management in the sidebar
Click the Password button next to admin
Enter a new secure password
5. (Optional) Configure Alert Messages
Edit plugins/ServerWebAdmin/config.yml to customize kick/ban messages:

Code (Text):
web:
  port: 8080
alerts:
  kick: "Custom kick message here..."
  ban_reason: "Custom ban reason"
  ban_kick: "Custom ban kick message"
Then run /webadmin reload (or /reload confirm) to apply changes.

6. Verify Firewall Access
Make sure port 8080 (or your custom port) is open in your server's firewall. For cloud/rented servers, check the hosting provider's firewall/security group settings.

7. (Optional) Add More Admin Users
Go to User Management → Add User
Enter a username and password
Click Add
8. Test Core Features
Server Status — Confirm TPS, RAM, uptime display correctly
Online Players — Verify player list appears when players are online
Console — Try running a command like say Hello from Web Admin
Weather/Times — Test clear, rain, thunder, day, night buttons
Whitelist / Bans — Verify management pages load and work
