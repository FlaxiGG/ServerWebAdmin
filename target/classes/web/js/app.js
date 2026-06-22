var currentUser='';
var permissions=[];
function hasPerm(p){return permissions.indexOf(p)!==-1;}
var heartbeatTimer=null;
(function loadTheme(){
  if(localStorage.getItem('theme')==='dark'){
    document.body.classList.add('dark');
  }
  if(localStorage.getItem('sidebar-collapsed')==='true'){
    document.body.classList.add('sidebar-collapsed');
  }
})();
(function checkAuthOnLoad(){
  fetch('/api/heartbeat').then(function(r){
    if(r.ok){
      try{permissions=JSON.parse(localStorage.getItem('adminPerms')||'[]');}catch(e){permissions=[];}
      var pagePerms = {console:'console.view',files:'files.view',players:'players.view',bans:'bans.view',whitelist:'whitelist.view',users:'users.view'};
      for(var page in pagePerms){
        if(!hasPerm(pagePerms[page])){
          var b=document.querySelector('.nav-btn[data-page="'+page+'"]');
          if(b)b.style.display='none';
        }
      }
      document.getElementById('login-page').style.display='none';
      var ac=document.getElementById('app-container');
      ac.classList.add('visible');
      startHeartbeat();
      loadPageContent(currentPage);
    }else{
      document.getElementById('login-page').style.display='flex';
    }
  }).catch(function(){
    document.getElementById('login-page').style.display='flex';
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
function toggleSidebar(){
  if(document.body.classList.contains('sidebar-collapsed')){
    document.body.classList.remove('sidebar-collapsed');
    localStorage.setItem('sidebar-collapsed','false');
  }else{
    document.body.classList.add('sidebar-collapsed');
    localStorage.setItem('sidebar-collapsed','true');
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
var confirmAction=null;
var confirmGetReason=null;
function showConfirm(title,msg,callback){
  document.getElementById('confirm-modal-title').textContent=title;
  document.getElementById('confirm-modal-msg').textContent=msg;
  document.getElementById('confirm-modal-input-wrap').style.display='none';
  document.getElementById('confirm-modal-btn').disabled=false;
  confirmAction=callback;
  confirmGetReason=null;
  document.getElementById('confirm-modal').classList.add('show');
}
function showConfirmWithReason(title,msg,placeholder,callback){
  document.getElementById('confirm-modal-title').textContent=title;
  document.getElementById('confirm-modal-msg').textContent=msg;
  document.getElementById('confirm-modal-input-wrap').style.display='block';
  var inp=document.getElementById('confirm-modal-reason');
  inp.value='';inp.placeholder=placeholder||'Reason\u2026';
  document.getElementById('confirm-modal-btn').disabled=true;
  confirmAction=null;
  confirmGetReason=callback;
  document.getElementById('confirm-modal').classList.add('show');
  setTimeout(function(){inp.focus()},200);
}
function closeConfirmModal(){
  document.getElementById('confirm-modal').classList.remove('show');
  confirmAction=null;confirmGetReason=null;
}
function doConfirm(){
  if(confirmGetReason){
    var r=document.getElementById('confirm-modal-reason').value.trim();
    if(!r)return;
    confirmGetReason(r);confirmGetReason=null;
  }else if(confirmAction){
    confirmAction();confirmAction=null;
  }
  document.getElementById('confirm-modal').classList.remove('show');
}
document.getElementById('confirm-modal-btn').addEventListener('click',doConfirm);
document.getElementById('confirm-modal').addEventListener('click',function(e){
  if(e.target===this)closeConfirmModal();
});
function doLogin(){
  var u=document.getElementById('login-username').value.trim();
  var p=document.getElementById('login-password').value.trim();
  if(!u||!p){showLoginError('Please enter username and password');return;}
  var btn=document.querySelector('#login-page .btn-primary');
  btn.disabled=true;btn.textContent='Signing in…';
  fetch('/api/login?username='+encodeURIComponent(u)+'&password='+encodeURIComponent(p),{method:'POST'})
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){
    btn.disabled=false;btn.textContent='Sign In';
    if(data.success){
      currentUser=data.username;
      permissions=data.permissions||[];
      localStorage.setItem('adminRole',data.role||'');
      localStorage.setItem('adminPerms',JSON.stringify(permissions));
      if(data.mustChange){
        document.getElementById('login-page').style.display='none';
        document.getElementById('app-container').classList.add('visible');
        startHeartbeat();
        showChangePasswordModal();
        return;
      }
      document.getElementById('login-page').style.display='none';
      document.getElementById('app-container').classList.add('visible');
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
  permissions=[];
  localStorage.removeItem('adminRole');
  localStorage.removeItem('adminPerms');
  document.getElementById('login-page').style.display='flex';
  document.getElementById('app-container').classList.remove('visible');
  document.getElementById('login-username').value='';
  document.getElementById('login-password').value='';
  clearPageTimers();
}
function showChangePasswordModal(){
  document.getElementById('login-page').style.display='none';
  document.getElementById('app-container').classList.add('visible');
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
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
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
  var pagePerms={console:'console.view',files:'files.view',players:'players.view',bans:'bans.view',whitelist:'whitelist.view',users:'users.view'};
  var req=pagePerms[page];
  if(req&&!hasPerm(req)){
    document.querySelectorAll('.page.active').forEach(function(p){p.classList.remove('active')});
    var t=document.getElementById('page-'+page);
    t.innerHTML='<div class="page-content"><div class="empty" style="padding:4rem 1rem;">You are not able to see this</div></div>';
    t.classList.add('active');
    return;
  }
  fetch('/pages/'+page+'.html')
  .then(function(r){return r.text()})
  .then(function(html){
    document.querySelectorAll('.page.active').forEach(function(p){p.classList.remove('active')});
    var target=document.getElementById('page-'+page);
    if(target){target.innerHTML=html;target.classList.add('active');}
    currentPage=page;location.hash=page;clearPageTimers();
    if(page==='dashboard'){loadPlayers();loadServerInfo();loadConsoleDash();pageTimers.p=setInterval(loadPlayers,5000);pageTimers.s=setInterval(loadServerInfo,5000);pageTimers.d=setInterval(loadConsoleDash,5000);
      setTimeout(function(){
        if(!hasPerm('server.reload')){var b=document.querySelector('.icon-btn-reload');if(b)b.style.display='none';}
        if(!hasPerm('server.stop')){var b=document.querySelector('.icon-btn-stop');if(b)b.style.display='none';}
      },100);}
    if(page==='console'){loadConsole();pageTimers.c=setInterval(loadConsole,3000);}
    if(page==='players'){loadPlayers();pageTimers.p=setInterval(loadPlayers,5000);}
    if(page==='bans'){loadBans();pageTimers.b=setInterval(loadBans,10000);}
    if(page==='files'){loadFileList('');}
    if(page==='users'){loadUsers();pageTimers.u=setInterval(loadUsers,30000);}
  });
}
function switchPage(page){
 document.querySelectorAll('.nav-btn.active').forEach(function(b){b.classList.remove('active')});
 document.querySelector('.nav-btn[data-page="'+page+'"]').classList.add('active');
 loadPageContent(page);
}
var hash=location.hash.replace('#','');
if(hash&&['dashboard','console','files','players','bans','whitelist','users'].indexOf(hash)!==-1){
 currentPage=hash;
 document.querySelector('.nav-btn.active').classList.remove('active');
 document.querySelector('.nav-btn[data-page=\"'+hash+'\"]').classList.add('active');
}else{location.hash='dashboard';}
function clearPageTimers(){
 for(var k in pageTimers){clearInterval(pageTimers[k]);}pageTimers={};
}
function loadPlayers(){
  fetch('/api/players')
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){
    if(data.success===false){
      var p=document.getElementById('players');if(p)p.innerHTML='<div class=\"empty\">Invalid or missing token.</div>';
      var pc=document.getElementById('player-count');if(pc)pc.textContent='';
      return;
    }
    var on=document.getElementById('online');if(on)on.textContent=data.online;
    var pc=document.getElementById('player-count');if(pc)pc.textContent=data.online+' online';
    var p=document.getElementById('players');
    if(!p)return;
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
        (hasPerm('player.ban')?'<select class=\"gm-select\" onchange=\"playerAction(\''+p.name+'\',\'gamemode \'+this.value)\"><option value=\"\">GM...</option><option value=\"survival\">Survival</option><option value=\"creative\">Creative</option><option value=\"adventure\">Adventure</option><option value=\"spectator\">Spectator</option></select>'+
        '<button class=\"btn btn-sm btn-accent-green\" onclick=\"playerAction(\''+p.name+'\',\'heal\')\">Heal</button>'+
        '<button class=\"btn btn-sm btn-accent-blue\" onclick=\"playerAction(\''+p.name+'\',\'feed\')\">Feed</button>'+
        '<button class=\"btn btn-sm btn-accent-pink\" onclick=\"playerAction(\''+p.name+'\',\'kill\')\">Kill</button>'+
        '<span class=\"divider\"></span>':'')+
        (hasPerm('player.ban')?'<button class=\"btn btn-goto\" onclick=\"showGoToModal(\''+p.name+'\')\">Go To</button>'+'<button class=\"btn btn-tp\" onclick=\"showTeleportModal(\''+p.name+'\')\">Teleport To</button>':'')+
        (hasPerm('player.kick')?'<button class=\"btn btn-kick\" onclick=\"kickPlayer(\''+p.name+'\')\">Kick</button>':'')+
        (hasPerm('player.ban')?'<button class=\"btn btn-ban\" onclick=\"banPlayer(\''+p.name+'\')\">Ban</button>':'')+
      '</div>';
    });
    p.innerHTML=html||'<div class=\"empty\">No players online</div>';
  }).catch(function(){
    var p=document.getElementById('players');if(p)p.innerHTML='<div class=\"empty\">Unable to load players.</div>';
  });
}
function playerAction(player,action){
 fetch('/api/player-action?player='+encodeURIComponent(player)+'&action='+encodeURIComponent(action),{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);setTimeout(loadPlayers,300);});
}
function sendCommand(){
 var cmd=document.getElementById('console-cmd').value.trim();
 if(!cmd)return;
  fetch('/api/command?command='+encodeURIComponent(cmd),{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);document.getElementById('console-cmd').value='';setTimeout(loadConsole,500);});
}
function loadConsole(){
  fetch('/api/console?lines=50')
 .then(function(r){
   if(r.status===403){var el=document.getElementById('console-full');if(el)el.textContent='You are not able to see this';return null;}
   return r.json();
 })
 .then(function(data){
   var el=document.getElementById('console-full');
    if(el)el.textContent=data.lines&&data.lines.length?data.lines.join('\n'):'The console is ready, waiting for new server output\u2026';
   var cnt=document.getElementById('console-line-count');
   if(cnt)cnt.textContent=data.totalLines?data.totalLines+' lines':'';
 }).catch(function(){});
}
function loadConsoleDash(){
  fetch('/api/console?lines=15')
 .then(function(r){
   if(r.status===403){var el=document.getElementById('console-dash');if(el)el.textContent='You are not able to see this';return null;}
   return r.json();
 })
 .then(function(data){
   var el=document.getElementById('console-dash');
    if(el)el.textContent=data.lines&&data.lines.length?data.lines.join('\n'):'The console is ready, waiting for new server output\u2026';
 }).catch(function(){});
}
function loadBans(){
  fetch('/api/bans')
 .then(function(r){
   if(r.status===403){document.getElementById('ban-list').innerHTML='<div class="empty">You are not able to see this</div>';return null;}
   return r.json();
 })
 .then(function(data){
   if(data.success===false)return;
   var html='';
   if(!data.length){html='<div class=\"empty\">No banned players</div>';}
   else{
     html+='<table class=\"tbl\"><thead><tr><th>Player</th><th>Reason</th><th>Source</th><th></th></tr></thead><tbody>';
     data.forEach(function(b){
       html+='<tr><td>'+b.name+'</td><td>'+b.reason+'</td><td>'+b.source+'</td>';
        html+='<td><button class=\"btn btn-sm btn-accent-red\" onclick=\"unbanPlayer(\''+b.name+'\')\">Unban</button></td></tr>';
     });
     html+='</tbody></table>';
   }
   document.getElementById('ban-list').innerHTML=html;
 }).catch(function(){});
}
function unbanPlayer(name){
  showConfirm('Unban Player','Are you sure you want to unban '+name+'?',function(){
    fetch('/api/unban?player='+encodeURIComponent(name),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadBans();});
  });
}
function loadWhitelist(){
  fetch('/api/whitelist')
 .then(function(r){
   if(r.status===403){document.getElementById('wl-list').innerHTML='<div class="empty">You are not able to see this</div>';return null;}
   return r.json();
 })
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
        html+='<tr><td>'+p.name+'</td><td style=\"font-size:0.6875rem;color:var(--text-muted);\">'+p.uuid+'</td>';
        html+='<td><button class=\"btn btn-sm btn-accent-red\" onclick=\"removeWhitelist(\''+p.name+'\')\">Remove</button></td></tr>';
     });
     html+='</tbody></table>';
   }
   document.getElementById('wl-list').innerHTML=html;
 }).catch(function(){});
}
function toggleWhitelist(){
  fetch('/api/whitelist/toggle',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);loadWhitelist();});
}
function addWhitelist(){
 var name=document.getElementById('wl-add-input').value.trim();
 if(!name){showToast('Please enter a player name');return;}
 fetch('/api/whitelist/add?player='+encodeURIComponent(name),{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);document.getElementById('wl-add-input').value='';loadWhitelist();});
}
function removeWhitelist(name){
  showConfirm('Remove from Whitelist','Are you sure you want to remove '+name+' from the whitelist?',function(){
    fetch('/api/whitelist/remove?player='+encodeURIComponent(name),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadWhitelist();});
  });
}
function kickPlayer(player){
  showConfirmWithReason('Kick Player','Are you sure you want to kick '+player+'?','Reason for kick\u2026',function(reason){
    fetch('/api/kick?player='+encodeURIComponent(player)+'&reason='+encodeURIComponent(reason),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadPlayers();});
  });
}
function banPlayer(player){
  showConfirmWithReason('Ban Player','Are you sure you want to ban '+player+'? They will be kicked from the server.','Reason for ban\u2026',function(reason){
    fetch('/api/ban?player='+encodeURIComponent(player)+'&reason='+encodeURIComponent(reason),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadPlayers();});
  });
}
function saveServer(){
 showToast('Saving world\u2026');
 fetch('/api/save',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);});
}
function reloadServer(){
  showConfirm('Reload Server','Are you sure you want to reload the server?',function(){
    showToast('Reloading server\u2026');
    fetch('/api/reload',{method:'POST'})
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){showToast(data.message);});
  });
}
function stopServer(){
  showConfirm('Stop Server','The world will be saved automatically before shutdown. All players will be disconnected.',function(){
    showToast('Saving world then stopping\u2026');
    fetch('/api/stop',{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);});
  });
}
function weatherClear(){
 showToast('Weather Clear\u2026');
 fetch('/api/clear',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);});
}
function weatherRain(){
 showToast('Weather Rain\u2026');
 fetch('/api/rain',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);});
}
function weatherThunder(){
 showToast('Weather Thunder\u2026');
 fetch('/api/thunder',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);});
}
function timeDay(){
 showToast('Time Day\u2026');
 fetch('/api/day',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);});
}
function timeNight(){
 showToast('Time Night\u2026');
 fetch('/api/night',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);});
}
function timeNoon(){
 showToast('Time Noon\u2026');
 fetch('/api/noon',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
 .then(function(data){showToast(data.message);});
}
function timeMidnight(){
 showToast('Time Midnight\u2026');
 fetch('/api/midnight',{method:'POST'})
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
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
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
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
 .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
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
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){
    if(data.success===false)return;
    if(data.motd) document.getElementById('server-motd').textContent=data.motd;
    if(data.mcVersion) document.getElementById('server-version').textContent=data.mcVersion;
    if(data.os) document.getElementById('sys-os').textContent=data.os;
    document.getElementById('uptime').textContent=formatUptime(data.uptimeSeconds);
    document.getElementById('ram-value').textContent=data.ramUsed;
    document.getElementById('ram-max').textContent=' / '+data.ramMax+' MB';
    var ramPct=Math.round(data.ramUsed/data.ramMax*100);
    document.getElementById('ram-fill').style.width=ramPct+'%';
    var cpu=document.getElementById('cpu');
    cpu.textContent=data.cpu;
    cpu.style.color=data.cpu!=='N/A'?(parseFloat(data.cpu)>70?'#e49494':parseFloat(data.cpu)>40?'#dbbc7c':'#8cbfa8'):'var(--text-heading)';
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
 .then(function(r){
   if(r.status===403){document.getElementById('user-list').innerHTML='<div class="empty">You are not able to see this</div>';return null;}
   return r.json();
 })
 .then(function(data){
   if(data.success===false)return;
    var html='';
    if(!data.length){html='<div class="empty">No users found</div>';}
    else{
      html+='<table class="tbl"><thead><tr><th>Username</th><th>Role</th><th></th></tr></thead><tbody>';
      data.forEach(function(u){
        html+='<tr><td>'+u.username+'</td><td style="text-transform:capitalize">'+u.role+'</td>';
        if(u.role!=='owner'&&hasPerm('users.edit')){
          html+='<td style="display:flex;gap:0.375rem;">';
          html+='<select class="gm-select" onchange="editUserRole(\''+u.username+'\',this.value)" style="font-size:0.6875rem;"><option value="">Role…</option><option value="admin">Admin</option><option value="moderator">Moderator</option><option value="viewer">Viewer</option></select>';
          html+='<button class="btn btn-sm btn-accent-blue" onclick="showChangePassword(\''+u.username+'\')">Password</button>';
          if(hasPerm('users.delete'))html+='<button class="btn btn-sm btn-accent-red" onclick="removeUser(\''+u.username+'\')">Remove</button>';
          html+='</td>';
        }else{
          html+='<td style="font-size:0.75rem;color:var(--text-muted);">Protected</td>';
        }
        html+='</tr>';
      });
      html+='</tbody></table>';
    }
    document.getElementById('user-list').innerHTML=html;
  }).catch(function(){});
}
function addUser(){
  var n=document.getElementById('user-add-name').value.trim();
  var p=document.getElementById('user-add-pass').value.trim();
  var r=document.getElementById('user-add-role').value;
  if(!n||!p){showToast('Please enter username and password');return;}
  fetch('/api/users/add?username='+encodeURIComponent(n)+'&password='+encodeURIComponent(p)+'&role='+encodeURIComponent(r),{method:'POST'})
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){
    showToast(data.message);
    document.getElementById('user-add-name').value='';
    document.getElementById('user-add-pass').value='';
    loadUsers();
  });
}
function editUserRole(name,newRole){
  if(!newRole)return;
  showConfirm('Change Role','Set '+name+' role to '+newRole+'?',function(){
    fetch('/api/users/edit?username='+encodeURIComponent(name)+'&role='+encodeURIComponent(newRole),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadUsers();});
  });
}
function removeUser(name){
  showConfirm('Remove User','Are you sure you want to remove user '+name+'?',function(){
    fetch('/api/users/remove?username='+encodeURIComponent(name),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadUsers();});
  });
}
function showChangePassword(name){
  var p=prompt('Enter new password for '+name+':');
  if(!p)return;
  fetch('/api/users/change-password?username='+encodeURIComponent(name)+'&password='+encodeURIComponent(p),{method:'POST'})
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){showToast(data.message);loadUsers();});
}

/* === FILE MANAGER === */

var currentFilePath='';
var currentFileName='';

function loadFileList(path){
  currentFilePath=path||'';
  fetch('/api/files/list?path='+encodeURIComponent(currentFilePath))
  .then(function(r){if(r.status===403){document.getElementById('file-list').innerHTML='<div class="empty">You are not able to see this</div>';return null;}return r.json();})
  .then(function(data){
    if(!data||!data.success)return;
    renderBreadcrumb(data.currentPath,data.parentPath);
    var html='';
    if(data.parentPath!==undefined&&data.parentPath!==null&&data.parentPath!=='/'){
      html+='<div class="file-row" onclick="loadFileList(\''+data.parentPath+'\')">'+
        '<div class="file-icon" style="font-size:1.2rem;">&#x1F4C1;</div>'+
        '<div class="file-name">..</div><div class="file-meta">Parent</div></div>';
    }
    var hasDel=hasPerm('files.delete'),hasRen=hasPerm('files.rename'),
        hasRead=hasPerm('files.read'),hasEdit=hasPerm('files.edit'),
        hasDown=hasPerm('files.download');
    if(data.files)data.files.forEach(function(f){
      var icon=f.isDir?'&#x1F4C1;':'&#x1F4C4;';
      html+='<div class="file-row" onclick="'+(
        f.isDir?('loadFileList(\''+(data.currentPath?data.currentPath+'/':'')+f.name+'\')'):
        '')+'">'+
        '<div class="file-icon">'+icon+'</div>'+
        '<div class="file-name">'+f.name+'</div>'+
        '<div class="file-meta">'+(f.isDir?'':formatFileSize(f.size))+'</div>'+
        '<div class="file-actions" onclick="event.stopPropagation()">';
      if(!f.isDir){
        if(hasRead)html+='<button class="btn btn-sm" style="background:var(--bg-input);color:var(--text);" onclick="viewFile(\''+(data.currentPath?data.currentPath+'/':'')+f.name+'\',\''+f.name+'\')">View</button>';
        if(hasEdit)html+='<button class="btn btn-sm" style="background:var(--bg-input);color:var(--text);" onclick="editFile(\''+(data.currentPath?data.currentPath+'/':'')+f.name+'\',\''+f.name+'\')">Edit</button>';
        if(hasDown)html+='<button class="btn btn-sm" style="background:var(--bg-input);color:var(--text);" onclick="downloadFile(\''+(data.currentPath?data.currentPath+'/':'')+f.name+'\')">Download</button>';
      }
      if(hasRen)html+='<button class="btn btn-sm" style="background:var(--bg-input);color:var(--text);" onclick="showRenamePrompt(\''+(data.currentPath?data.currentPath+'/':'')+f.name+'\',\''+f.name+'\')">Rename</button>';
      if(hasDel)html+='<button class="btn btn-sm btn-danger" onclick="deleteFileConfirm(\''+(data.currentPath?data.currentPath+'/':'')+f.name+'\')">Delete</button>';
      html+='</div></div>';
    });
    document.getElementById('file-list').innerHTML=html||'<div class="file-empty">This folder is empty</div>';
  }).catch(function(){});
}

function renderBreadcrumb(currentPath,parentPath){
  var html='<span onclick="loadFileList(\'\')">&#x1F3E0;</span> <span class="sep">&rsaquo;</span> ';
  if(!currentPath){html+='<span class="current">/</span>';}
  else {
    var parts=currentPath.split('/');
    var cum='';
    for(var i=0;i<parts.length;i++){
      cum+=(cum?'/':'')+parts[i];
      if(i===parts.length-1)html+='<span class="current">'+parts[i]+'</span>';
      else html+='<span onclick="loadFileList(\''+cum+'\')" class="file-link">'+parts[i]+'</span> <span class="sep">&rsaquo;</span> ';
    }
  }
  document.getElementById('file-breadcrumb').innerHTML=html;
}

function formatFileSize(bytes){
  if(bytes<1024)return bytes+' B';
  if(bytes<1048576)return (bytes/1024).toFixed(1)+' KB';
  return (bytes/1048576).toFixed(1)+' MB';
}

function viewFile(path,name){
  fetch('/api/files/read?path='+encodeURIComponent(path))
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){
    if(!data||!data.success){showToast(data.message||'Failed to read file');return;}
    document.getElementById('file-viewer-title').textContent=name;
    generateLineNumbers('viewer-textarea','viewer-gutter',data.content);
    document.getElementById('file-viewer-modal').classList.add('show');
  });
}

function editFile(path,name){
  currentFilePath=path;currentFileName=name;
  fetch('/api/files/read?path='+encodeURIComponent(path))
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){
    if(!data||!data.success){showToast(data.message||'Failed to read file');return;}
    document.getElementById('file-editor-status').textContent='Editing: '+name+' ('+formatFileSize(data.size)+')';
    generateLineNumbers('editor-textarea','editor-gutter',data.content);
    document.getElementById('file-editor-modal').classList.add('show');
    setTimeout(function(){document.getElementById('editor-textarea').focus()},200);
  });
}

function generateLineNumbers(textareaId,gutterId,content){
  var lines=content.split('\n');
  var ta=document.getElementById(textareaId);
  ta.value=content;
  ta.rows=Math.max(15,lines.length);
  var num='';
  for(var i=1;i<=lines.length;i++)num+=i+'\n';
  document.getElementById(gutterId).textContent=num;
  // Sync scroll
  ta.onscroll=function(){
    document.getElementById(gutterId).scrollTop=ta.scrollTop;
  };
}

function saveFile(){
  var ta=document.getElementById('editor-textarea');
  var content=ta.value;
  var body=JSON.stringify({path:currentFilePath,content:content});
  fetch('/api/files/save',{method:'POST',body:body})
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){
    if(data&&data.success){showToast('File saved');closeFileEditor();loadFileList(currentFilePath.replace(/\/[^\/]*$/,''));}
    else showToast(data.message||'Save failed');
  }).catch(function(){showToast('Save failed');});
}

function closeFileViewer(){
  document.getElementById('file-viewer-modal').classList.remove('show');
}

function closeFileEditor(){
  document.getElementById('file-editor-modal').classList.remove('show');
}

function downloadFile(path){
  window.open('/api/files/download?path='+encodeURIComponent(path),'_blank');
}

function uploadFile(input){
  var file=input.files[0];
  if(!file)return;
  if(file.size>5242880){showToast('File too large (max 5MB)');return;}
  var form=new FormData();
  form.append('file',file);
  form.append('path',currentFilePath);
  var btn=document.getElementById('upload-btn');
  btn.disabled=true;btn.textContent='Uploading…';
  fetch('/api/files/upload?path='+encodeURIComponent(currentFilePath),{method:'POST',body:form})
  .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
  .then(function(data){
    btn.disabled=false;btn.textContent='Upload';
    showToast(data.message);loadFileList(currentFilePath);
  }).catch(function(){btn.disabled=false;btn.textContent='Upload';showToast('Upload failed');});
  input.value='';
}

function showNewFolderPrompt(){
  showConfirmWithReason('New Folder','Enter a name for the new folder:','Folder name\u2026',function(name){
    fetch('/api/files/mkdir?path='+encodeURIComponent((currentFilePath?currentFilePath+'/':'')+name),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadFileList(currentFilePath);});
  });
}

function showRenamePrompt(oldPath,oldName){
  showConfirmWithReason('Rename','Rename '+oldName+' to:','New name\u2026',function(name){
    if(name===oldName)return;
    fetch('/api/files/rename?old='+encodeURIComponent(oldPath)+'&new='+encodeURIComponent(name),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadFileList(currentFilePath);});
  });
}

function deleteFileConfirm(path){
  showConfirm('Delete','Are you sure you want to delete this?',function(){
    fetch('/api/files/delete?path='+encodeURIComponent(path),{method:'POST'})
    .then(function(r){if(r.status===403){showToast('You are not allowed to do this');return null;}return r.json();})
    .then(function(data){showToast(data.message);loadFileList(currentFilePath);});
  });
}
