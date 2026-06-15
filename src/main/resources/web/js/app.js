var currentUser='';
var heartbeatTimer=null;
(function loadTheme(){
  if(localStorage.getItem('theme')==='dark'){
    document.body.classList.add('dark');
  }
})();
(function checkAuthOnLoad(){
  fetch('/api/heartbeat').then(function(r){
    if(r.ok){
      document.getElementById('login-page').style.display='none';
      var ac=document.getElementById('app-container');
      ac.style.display='flex';ac.style.flexDirection='column';ac.style.minHeight='100vh';
      startHeartbeat();
      loadPageContent(currentPage);
    }else{
      document.getElementById('login-page').style.display='flex';
      document.getElementById('app-container').style.display='none';
    }
  }).catch(function(){
    document.getElementById('login-page').style.display='flex';
    document.getElementById('app-container').style.display='none';
  });
})();
function toggleTheme(){
  if(document.body.classList.contains('dark')){
    document.body.classList.remove('dark');
    localStorage.setItem('theme','light');
  }else{
    document.body.classList.add('dark');
    localStorage.setItem('theme','dark');
  }
}
function startHeartbeat(){
  stopHeartbeat();
  heartbeatTimer=setInterval(heartbeat,120000);
  heartbeat();
}
function stopHeartbeat(){
  if(heartbeatTimer){clearInterval(heartbeatTimer);heartbeatTimer=null;}
}
function heartbeat(){
  fetch('/api/heartbeat').then(function(r){
    if(r.status===401){doLogout();showToast('Session expired. Please login again.');}
  }).catch(function(){});
}
function showToast(msg){
 var t=document.getElementById('toast');
 t.textContent=msg;t.classList.add('show');
 setTimeout(function(){t.classList.remove('show')},2800)
}
function doLogin(){
  var u=document.getElementById('login-username').value.trim();
  var p=document.getElementById('login-password').value.trim();
  if(!u||!p){showLoginError('Please enter username and password');return;}
  var btn=document.querySelector('#login-page .btn-primary');
  btn.disabled=true;btn.textContent='Signing in…';
  fetch('/api/login?username='+encodeURIComponent(u)+'&password='+encodeURIComponent(p),{method:'POST'})
  .then(function(r){return r.json()})
  .then(function(data){
    btn.disabled=false;btn.textContent='Sign In';
    if(data.success){
      currentUser=data.username;
      if(data.mustChange){
        document.getElementById('login-page').style.display='none';
        var ac=document.getElementById('app-container');
        ac.style.display='flex';ac.style.flexDirection='column';ac.style.minHeight='100vh';
        startHeartbeat();
        showChangePasswordModal();
        return;
      }
      document.getElementById('login-page').style.display='none';
      var ac=document.getElementById('app-container');
      ac.style.display='flex';ac.style.flexDirection='column';ac.style.minHeight='100vh';
      startHeartbeat();
      loadPageContent('dashboard');
    }else{
      showLoginError(data.message);
    }
  }).catch(function(){btn.disabled=false;btn.textContent='Sign In';showLoginError('Connection error');});
}
function showLoginError(msg){
  var e=document.getElementById('login-error');
  e.textContent=msg;e.style.display='block';
  setTimeout(function(){e.style.display='none'},4000);
}
function doLogout(){
  stopHeartbeat();
  fetch('/api/logout',{method:'POST'});
  currentUser='';
  document.getElementById('login-page').style.display='flex';
  document.getElementById('app-container').style.display='none';
  document.getElementById('login-username').value='';
  document.getElementById('login-password').value='';
  clearPageTimers();
}
function showChangePasswordModal(){
  document.getElementById('login-page').style.display='none';
  var ac=document.getElementById('app-container');
  ac.style.display='flex';ac.style.flexDirection='column';ac.style.minHeight='100vh';
  startHeartbeat();
  document.getElementById('changepw-modal').classList.add('show');
}
function doChangePassword(){
  var oldPw=document.getElementById('changepw-old').value.trim();
  var newPw=document.getElementById('changepw-new').value.trim();
  var confirmPw=document.getElementById('changepw-confirm').value.trim();
  if(!oldPw||!newPw||!confirmPw){showToast('Please fill all fields');return;}
  if(newPw!==confirmPw){showToast('Passwords do not match');return;}
  fetch('/api/change-password?username='+encodeURIComponent(currentUser)+'&oldPassword='+encodeURIComponent(oldPw)+'&newPassword='+encodeURIComponent(newPw)+'&confirmPassword='+encodeURIComponent(confirmPw),{method:'POST'})
  .then(function(r){return r.json()})
  .then(function(data){
     if(data.success){
       showToast('Password changed successfully');
       document.getElementById('changepw-modal').classList.remove('show');
       loadPageContent('dashboard');
    }else{
      showToast(data.message||'Failed to change password');
    }
  }).catch(function(){showToast('Connection error');});
}
var currentPage='dashboard';
var pageTimers={};
function loadPageContent(page){
 fetch('/pages/'+page+'.html')
 .then(function(r){return r.text()})
 .then(function(html){
   document.querySelectorAll('.page.active').forEach(function(p){p.classList.remove('active')});
   var target=document.getElementById('page-'+page);
   if(target){target.innerHTML=html;target.classList.add('active');}
   currentPage=page;location.hash=page;clearPageTimers();
   if(page==='dashboard'){loadPlayers();loadServerInfo();loadConsoleDash();pageTimers.p=setInterval(loadPlayers,5000);pageTimers.s=setInterval(loadServerInfo,5000);pageTimers.d=setInterval(loadConsoleDash,5000);}
   if(page==='console'){loadConsole();pageTimers.c=setInterval(loadConsole,3000);}
   if(page==='bans')loadBans();
   if(page==='whitelist')loadWhitelist();
   if(page==='users')loadUsers();
 });
}
function switchPage(page){
 document.querySelectorAll('.nav-btn.active').forEach(function(b){b.classList.remove('active')});
 document.querySelector('.nav-btn[data-page="'+page+'"]').classList.add('active');
 loadPageContent(page);
}
var hash=location.hash.replace('#','');
if(hash&&['dashboard','console','bans','whitelist','users'].indexOf(hash)!==-1){
 currentPage=hash;
 document.querySelector('.nav-btn.active').classList.remove('active');
 document.querySelector('.nav-btn[data-page=\"'+hash+'\"]').classList.add('active');
}else{location.hash='dashboard';}
function clearPageTimers(){
 for(var k in pageTimers){clearInterval(pageTimers[k]);}pageTimers={};
}
function loadPlayers(){
 fetch('/api/players')
 .then(function(r){return r.json()})
 .then(function(data){
   if(data.success===false){
     document.getElementById('players').innerHTML='<div class=\"empty\">Invalid or missing token. Please set your admin token.</div>';
     document.getElementById('player-count').textContent='';
     return;
   }
   document.getElementById('online').textContent=data.online;
   document.getElementById('player-count').textContent=data.online+' online';
   var html='';
   data.players.forEach(function(p){
     var hpPct=Math.round(p.health*5);var hpC=hpPct>60?'#8cbfa8':hpPct>25?'#dbbc7c':'#e49494';
     html+='<div class=\"player-row\">'+
       '<div class=\"player-info\">'+
         '<span class=\"player-name\">'+p.name+'</span>'+
         '<div class=\"player-meta\">'+
           '<span class=\"player-coords\">XYZ: '+p.x+' / '+p.y+' / '+p.z+'</span>'+
           '<span class=\"player-gm\">'+p.gamemode+'</span>'+
           '<span>HP <span class=\"hp-bar\"><span class=\"hp-fill\" style=\"width:'+hpPct+'%;background:'+hpC+'\"></span></span> '+p.health+'</span>'+
         '</div>'+
       '</div>'+
     '</div>'+
     '<div style=\"display:flex;gap:0.375rem;flex-wrap:wrap;margin-top:0.5rem;align-items:center;\">'+
       '<select class=\"gm-select\" onchange=\"playerAction(\''+p.name+'\',\'gamemode \'+this.value)\"><option value=\"\">GM...</option><option value=\"survival\">Survival</option><option value=\"creative\">Creative</option><option value=\"adventure\">Adventure</option><option value=\"spectator\">Spectator</option></select>'+
       '<button class=\"btn\" style=\"background:#c4e8d4;color:#3a5c4a;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"playerAction(\''+p.name+'\',\'heal\')\">Heal</button>'+
       '<button class=\"btn\" style=\"background:#d4dce8;color:#3a4a5c;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"playerAction(\''+p.name+'\',\'feed\')\">Feed</button>'+
       '<button class=\"btn\" style=\"background:#e8d4e0;color:#5c3a4a;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"playerAction(\''+p.name+'\',\'kill\')\">Kill</button>'+
       '<span style=\"color:#e5ded5;margin:0 0.25rem;font-size:0.75rem;\">|</span>'+
       '<button class=\"btn btn-goto\" onclick=\"showGoToModal(\''+p.name+'\')\">Go To</button>'+
       '<button class=\"btn btn-tp\" onclick=\"showTeleportModal(\''+p.name+'\')\">Teleport To</button>'+
       '<button class=\"btn btn-kick\" onclick=\"kickPlayer(\''+p.name+'\')\">Kick</button>'+
       '<button class=\"btn btn-ban\" onclick=\"banPlayer(\''+p.name+'\')\">Ban</button>'+
     '</div>';
   });
   document.getElementById('players').innerHTML=html||'<div class=\"empty\">No players online</div>';
 }).catch(function(){
   document.getElementById('players').innerHTML='<div class=\"empty\">Unable to load players. Check your token and connection.</div>';
 });
}
function playerAction(player,action){
 fetch('/api/player-action?player='+encodeURIComponent(player)+'&action='+encodeURIComponent(action),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);setTimeout(loadPlayers,300);});
}
function sendCommand(){
 var cmd=document.getElementById('console-cmd').value.trim();
 if(!cmd)return;
  fetch('/api/command?command='+encodeURIComponent(cmd),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);document.getElementById('console-cmd').value='';setTimeout(loadConsole,500);});
}
function loadConsole(){
  fetch('/api/console?lines=50')
 .then(function(r){return r.json()})
 .then(function(data){
   var el=document.getElementById('console-full');
   if(el)el.textContent=data.lines?data.lines.join('\n'):'No output';
   var cnt=document.getElementById('console-line-count');
   if(cnt)cnt.textContent=data.totalLines?data.totalLines+' lines':'';
 }).catch(function(){});
}
function loadConsoleDash(){
  fetch('/api/console?lines=15')
 .then(function(r){return r.json()})
 .then(function(data){
   var el=document.getElementById('console-dash');
   if(el)el.textContent=data.lines?data.lines.join('\n'):'No output';
 }).catch(function(){});
}
function loadBans(){
  fetch('/api/bans')
 .then(function(r){return r.json()})
 .then(function(data){
   if(data.success===false)return;
   var html='';
   if(!data.length){html='<div class=\"empty\">No banned players</div>';}
   else{
     html+='<table class=\"tbl\"><thead><tr><th>Player</th><th>Reason</th><th>Source</th><th></th></tr></thead><tbody>';
     data.forEach(function(b){
       html+='<tr><td>'+b.name+'</td><td>'+b.reason+'</td><td>'+b.source+'</td>';
       html+='<td><button class=\"btn\" style=\"background:#ecc8c8;color:#6b3a3a;font-size:0.6875rem;padding:0.25rem 0.625rem;\" onclick=\"unbanPlayer(\''+b.name+'\')\">Unban</button></td></tr>';
     });
     html+='</tbody></table>';
   }
   document.getElementById('ban-list').innerHTML=html;
 }).catch(function(){});
}
function unbanPlayer(name){
 if(!confirm('Unban '+name+'?'))return;
 fetch('/api/unban?player='+encodeURIComponent(name),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);loadBans();});
}
function loadWhitelist(){
  fetch('/api/whitelist')
 .then(function(r){return r.json()})
 .then(function(data){
   if(data.success===false)return;
   var toggle=document.getElementById('wl-toggle');
   if(data.enabled){toggle.classList.add('on');}else{toggle.classList.remove('on');}
   document.getElementById('wl-status').textContent=data.enabled?'Enabled':'Disabled';
   var html='';
   if(!data.players||!data.players.length){html='<div class=\"empty\">No whitelisted players</div>';}
   else{
     html+='<table class=\"tbl\"><thead><tr><th>Player</th><th>UUID</th><th></th></tr></thead><tbody>';
     data.players.forEach(function(p){
       html+='<tr><td>'+p.name+'</td><td style=\"font-size:0.6875rem;color:#8a847c;\">'+p.uuid+'</td>';
       html+='<td><button class=\"btn\" style=\"background:#ecc8c8;color:#6b3a3a;font-size:0.6875rem;padding:0.25rem 0.625rem;\" onclick=\"removeWhitelist(\''+p.name+'\')\">Remove</button></td></tr>';
     });
     html+='</tbody></table>';
   }
   document.getElementById('wl-list').innerHTML=html;
 }).catch(function(){});
}
function toggleWhitelist(){
  fetch('/api/whitelist/toggle',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);loadWhitelist();});
}
function addWhitelist(){
 var name=document.getElementById('wl-add-input').value.trim();
 if(!name){showToast('Please enter a player name');return;}
 fetch('/api/whitelist/add?player='+encodeURIComponent(name),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);document.getElementById('wl-add-input').value='';loadWhitelist();});
}
function removeWhitelist(name){
 if(!confirm('Remove '+name+' from whitelist?'))return;
 fetch('/api/whitelist/remove?player='+encodeURIComponent(name),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);loadWhitelist();});
}
function kickPlayer(player){
 if(!confirm('Kick '+player+'?')) return;
 fetch('/api/kick?player='+encodeURIComponent(player),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);loadPlayers();});
}
function banPlayer(player){
 if(!confirm('Ban '+player+'?')) return;
 fetch('/api/ban?player='+encodeURIComponent(player),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);loadPlayers();});
}
function saveServer(){
 showToast('Saving world\u2026');
 fetch('/api/save',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function reloadServer(){
 if(!confirm('Are you sure you want to reload the server?')) return;
 showToast('Reloading server\u2026');
 fetch('/api/reload',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function stopServer(){
 if(!confirm('Stop the server? The world will be saved automatically before shutdown. All players will be disconnected.')) return;
 showToast('Saving world then stopping\u2026');
 fetch('/api/stop',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function weatherClear(){
 showToast('Weather Clear\u2026');
 fetch('/api/clear',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function weatherRain(){
 showToast('Weather Rain\u2026');
 fetch('/api/rain',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function weatherThunder(){
 showToast('Weather Thunder\u2026');
 fetch('/api/thunder',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function timeDay(){
 showToast('Time Day\u2026');
 fetch('/api/day',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function timeNight(){
 showToast('Time Night\u2026');
 fetch('/api/night',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function timeNoon(){
 showToast('Time Noon\u2026');
 fetch('/api/noon',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
function timeMidnight(){
 showToast('Time Midnight\u2026');
 fetch('/api/midnight',{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
var tpFromPlayer='';
function showTeleportModal(player){
 tpFromPlayer=player;
 document.getElementById('tp-modal-desc').textContent='Teleport '+player+' to:';
 document.getElementById('tp-target').value='';
 document.getElementById('tp-modal').classList.add('show');
 setTimeout(function(){document.getElementById('tp-target').focus()},100);
}
function closeTeleportModal(){
 document.getElementById('tp-modal').classList.remove('show');
}
function doTeleport(){
 var toPlayer=document.getElementById('tp-target').value.trim();
 if(!toPlayer){showToast('Please enter a target player name');return;}
 closeTeleportModal();
 fetch('/api/teleport?from='+encodeURIComponent(tpFromPlayer)+'&to='+encodeURIComponent(toPlayer),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
document.getElementById('tp-modal').addEventListener('click',function(e){
 if(e.target===this) closeTeleportModal();
});
var gotoToPlayer='';
function showGoToModal(player){
 gotoToPlayer=player;
 document.getElementById('goto-modal-desc').textContent='Go to '+player+'. Enter your player name:';
 document.getElementById('goto-admin').value='';
 document.getElementById('goto-modal').classList.add('show');
 setTimeout(function(){document.getElementById('goto-admin').focus()},100);
}
function closeGoToModal(){
 document.getElementById('goto-modal').classList.remove('show');
}
function doGoTo(){
 var adminName=document.getElementById('goto-admin').value.trim();
 if(!adminName){showToast('Please enter your player name');return;}
 closeGoToModal();
 fetch('/api/teleport?from='+encodeURIComponent(adminName)+'&to='+encodeURIComponent(gotoToPlayer),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);});
}
document.getElementById('goto-modal').addEventListener('click',function(e){
 if(e.target===this) closeGoToModal();
});
document.addEventListener('keydown',function(e){
 if(e.key==='Escape'){closeTeleportModal();closeGoToModal();}
 if(e.key==='Enter'&&currentPage==='console'&&document.activeElement===document.getElementById('console-cmd')){sendCommand();}
});
function loadServerInfo(){
  fetch('/api/server')
 .then(function(r){return r.json()})
 .then(function(data){
   if(data.success===false)return;
   document.getElementById('ram').textContent=data.ramUsed+' / '+data.ramMax+' MB';
   document.getElementById('uptime').textContent=formatUptime(data.uptimeSeconds);
   if(data.motd) document.getElementById('server-motd').textContent=data.motd;
   var tps=document.getElementById('tps');
   tps.textContent=data.tps;
   tps.style.color=data.tps>=18?'#8cbfa8':data.tps>=13?'#dbbc7c':'#e49494';
 }).catch(function(){});
}
function formatUptime(seconds){
 var h=Math.floor(seconds/3600);
 var m=Math.floor((seconds%3600)/60);
 var s=seconds%60;
 return h+'h '+m+'m '+s+'s';
}
function loadUsers(){
  fetch('/api/users')
 .then(function(r){return r.json()})
 .then(function(data){
   if(data.success===false)return;
   var html='';
   if(!data.length){html='<div class=\"empty\">No users found</div>';}
   else{
     html+='<table class=\"tbl\"><thead><tr><th>Username</th><th></th></tr></thead><tbody>';
     data.forEach(function(u){
       html+='<tr><td>'+u.username+'</td>';
       html+='<td style=\"display:flex;gap:0.375rem;\">';
       html+='<button class=\"btn\" style=\"background:#d4dce8;color:#3a4a5c;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"showChangePassword(\''+u.username+'\')\">Password</button>';
       html+='<button class=\"btn\" style=\"background:#ecc8c8;color:#6b3a3a;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"removeUser(\''+u.username+'\')\">Remove</button>';
       html+='</td></tr>';
     });
     html+='</tbody></table>';
   }
   document.getElementById('user-list').innerHTML=html;
 }).catch(function(){});
}
function addUser(){
 var n=document.getElementById('user-add-name').value.trim();
 var p=document.getElementById('user-add-pass').value.trim();
 if(!n||!p){showToast('Please enter username and password');return;}
 fetch('/api/users/add?username='+encodeURIComponent(n)+'&password='+encodeURIComponent(p),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){
   showToast(data.message);
   document.getElementById('user-add-name').value='';
   document.getElementById('user-add-pass').value='';
   loadUsers();
 });
}
function removeUser(name){
 if(!confirm('Remove user '+name+'?'))return;
  fetch('/api/users/remove?username='+encodeURIComponent(name),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);loadUsers();});
}
function showChangePassword(name){
 var p=prompt('Enter new password for '+name+':');
 if(!p)return;
  fetch('/api/users/change-password?username='+encodeURIComponent(name)+'&password='+encodeURIComponent(p),{method:'POST'})
 .then(function(r){return r.json()})
 .then(function(data){showToast(data.message);loadUsers();});
}