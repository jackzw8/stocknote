package com.stocknote.feature.ui

/*
 * 由 output/_gen_plane_shooter.py 从 plane-shooter.html 自动生成 —— **不要手改**。
 *
 * 内嵌小游戏「星际战机」（老周 2026-10-04）：探索页 →「星际战机」，
 * 页面用 LocalHtmlView 把这份 HTML 直接喂给平台 WebView 渲染。
 *
 * ⚠️ 拆成多片是为了绕开 JVM 常量池单个字符串 65535 字节的上限
 *（整份 HTML 约 76963 字符、且含中文，单片必超）。
 */
private val PlaneShooterHtmlChunks: List<String> = listOf(
    """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="theme-color" content="#05070f">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<title>星际战机 · 100 关</title>
<style>
  *{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent;}
  html,body{
    width:100%;height:100%;overflow:hidden;background:#04060d;color:#e8f3ff;
    font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Helvetica Neue","Microsoft YaHei",sans-serif;
    -webkit-user-select:none;user-select:none;touch-action:none;overscroll-behavior:none;
  }
  @supports (height:100dvh){ html,body{height:100dvh;} }
  #stage{
    position:relative;width:100%;height:100%;max-width:480px;margin:0 auto;overflow:hidden;
    background:#05070f;box-shadow:0 0 80px rgba(70,150,255,.18);
  }
  #game{position:absolute;inset:0;width:100%;height:100%;display:block;}

  /* ---------- HUD ---------- */
  #hud{position:absolute;inset:0;pointer-events:none;transition:opacity .3s;}
  #hud.hide{opacity:0;}
  .hud-bar{
    position:absolute;top:0;left:0;right:0;display:flex;align-items:flex-start;justify-content:space-between;
    padding:calc(env(safe-area-inset-top) + 12px) 16px 0;gap:10px;
  }
  .lbl{font-size:9.5px;letter-spacing:.2em;color:#7ea4cc;text-transform:uppercase;font-weight:600;}
  .score-wrap .val{
    font-size:24px;font-weight:800;line-height:1.08;letter-spacing:.01em;
    color:#eaf7ff;text-shadow:0 0 16px rgba(90,190,255,.7);font-variant-numeric:tabular-nums;
  }
  .score-wrap .sub{font-size:10.5px;color:#6f8fb3;margin-top:2px;font-variant-numeric:tabular-nums;}
  .mid{text-align:center;}
  .hearts{display:flex;gap:4px;margin-top:6px;}
  .hearts span{font-size:15px;line-height:1;color:#ff5470;text-shadow:0 0 10px rgba(255,70,110,.85);}
  .power-wrap{text-align:right;}
  .power-track{display:flex;gap:3px;margin-top:6px;justify-content:flex-end;}
  .power-track i{display:block;width:9px;height:9px;border-radius:2px;background:rgba(120,170,220,.22);}
  .power-track i.on{background:linear-gradient(180deg,#ffe79a,#ffb703);box-shadow:0 0 8px rgba(255,190,60,.9);}

  /* 关卡进度条 */
  .prog{
    position:absolute;left:16px;right:16px;top:calc(env(safe-area-inset-top) + 74px);
    height:4px;border-radius:3px;background:rgba(120,170,220,.16);overflow:hidden;
  }
  .prog-fill{
    height:100%;width:0%;border-radius:3px;
    background:linear-gradient(90deg,#4fd2ff,#7cf3c0);box-shadow:0 0 10px rgba(90,220,255,.8);
    transition:width .18s linear,background .3s;
  }
  .prog-fill.boss{background:linear-gradient(90deg,#ff4d6d,#ff9a3a);box-shadow:0 0 12px rgba(255,90,110,.9);}

  #muteBtn{
    position:absolute;left:calc(env(safe-area-inset-left) + 12px);bottom:calc(env(safe-area-inset-bottom) + 12px);
    width:38px;height:38px;border-radius:12px;border:1px solid rgba(120,180,240,.22);
    background:rgba(18,32,58,.55);color:#a9cbe8;font-size:16px;line-height:1;
    display:flex;align-items:center;justify-content:center;pointer-events:auto;backdrop-filter:blur(6px);
    transition:transform .12s,background .2s;
  }
  #muteBtn:active{transform:scale(.9);}

  /* ---------- 关卡横幅 ---------- */
  #banner{
    position:absolute;left:0;right:0;top:38%;text-align:center;pointer-events:none;
    opacity:0;transform:scale(.86);transition:opacity .35s ease,transform .35s ease;
  }
  #banner.show{opacity:1;transform:scale(1);}
  .b-title{
    font-size:36px;font-weight:900;letter-spacing:.14em;
    background:linear-gradient(180deg,#ffffff,#7fd4ff 60%,#3a8dff);
    -webkit-background-clip:text;background-clip:text;color:transparent;
    filter:drop-shadow(0 0 22px rgba(80,180,255,.75));
  }
  .b-title.warn{background:linear-gradient(180deg,#fff,#ffb0be 60%,#ff2d55);-webkit-background-clip:text;background-clip:text;filter:drop-shadow(0 0 24px rgba(255,60,90,.8));}
  .b-sub{margin-top:10px;font-size:13px;letter-spacing:.34em;color:#9dbbd8;padding-left:.34em;}

  /* ---------- 覆盖层 ---------- */
  #overlay{
    position:absolute;inset:0;display:flex;align-items:center;justify-content:center;padding:22px;
    background:radial-gradient(90% 60% at 50% 40%,rgba(16,32,64,.6) 0%,rgba(4,6,13,.92) 100%);
    backdrop-filter:blur(3px);transition:opacity .28s;overflow-y:auto;
  }
  #overlay.hide{opacity:0;pointer-events:none;}
  .panel{
    width:100%;max-width:330px;text-align:center;margin:auto;
    background:linear-gradient(180deg,rgba(20,36,68,.88),rgba(10,18,38,.88));
    border:1px solid rgba(110,180,255,.22);border-radius:22px;
    padding:28px 22px 22px;box-shadow:0 20px 60px rgba(0,0,0,.55),inset 0 1px 0 rgba(255,255,255,.07);
  }
  .panel.hide{display:none;}
  .title{
    font-size:32px;font-weight:900;letter-spacing:.05em;line-height:1.18;
    background:linear-gradient(180deg,#ffffff 0%,#7fd4ff 55%,#3a8dff 100%);
    -webkit-background-clip:text;background-clip:text;color:transparent;
    filter:drop-shadow(0 0 18px rgba(80,180,255,.55));
  }
  .sub{margin-top:10px;font-size:12.5px;color:#9dbbd8;line-height:1.8;letter-spacing:.06em;}
  .tips{
    margin:18px 0 20px;padding:13px 12px;border-radius:14px;text-align:left;
    background:rgba(10,22,44,.66);border:1px solid rgba(110,180,255,.14);
    font-size:12px;color:#a9c4de;line-height:2.05;
  }
  .tips .k{color:#7fd4ff;font-weight:700;}
  .tips .p{color:#ffd166;font-weight:700;}
  .tips .h{color:#ff5d8f;font-weight:700;}
  .tips .b{color:#ff8a6b;font-weight:700;}
  .btn{
    display:block;width:100%;padding:15px 0;border:none;border-radius:15px;
    font-size:17px;font-weight:800;letter-spacing:.12em;color:#04203f;cursor:pointer;
    background:linear-gradient(180deg,#a8e4ff,#3ba7ff 60%,#1a72e0);
    box-shadow:0 10px 26px rgba(40,140,255,.45),inset 0 1px 0 rgba(255,255,255,.6);
    transition:transform .12s,filter .2s;font-family:inherit;
  }
  .btn""",
    """:active{transform:scale(.965);filter:brightness(1.08);}
  .btn.ghost{
    margin-top:10px;background:rgba(20,40,72,.8);color:#9fc8ea;
    box-shadow:none;border:1px solid rgba(110,180,255,.25);font-size:14px;padding:12px 0;
  }
  .best{margin-top:14px;font-size:11.5px;color:#7e9cbb;letter-spacing:.08em;font-variant-numeric:tabular-nums;}
  .best b{color:#eaf7ff;font-size:13.5px;}
  .over-title{font-size:20px;font-weight:800;color:#ff8098;letter-spacing:.2em;text-shadow:0 0 20px rgba(255,80,120,.5);}
  .final{
    font-size:50px;font-weight:900;line-height:1.1;margin:6px 0 2px;font-variant-numeric:tabular-nums;
    background:linear-gradient(180deg,#ffffff,#8fd8ff);-webkit-background-clip:text;background-clip:text;color:transparent;
    filter:drop-shadow(0 0 22px rgba(90,190,255,.5));
  }
  .stage-reach{margin-top:4px;font-size:13px;color:#9dbbd8;letter-spacing:.12em;}
  .stage-reach b{color:#7fd4ff;font-size:17px;}
  .new-record{margin-top:8px;font-size:11.5px;font-weight:700;color:#ffd166;letter-spacing:.22em;}
  .win-title{
    font-size:30px;font-weight:900;letter-spacing:.1em;
    background:linear-gradient(180deg,#fff8d0,#ffd166 55%,#ff9a3a);
    -webkit-background-clip:text;background-clip:text;color:transparent;
    filter:drop-shadow(0 0 24px rgba(255,190,60,.7));
  }

  /* ---------- 暂停按钮 ---------- */
  .pause-wrap{display:flex;flex-direction:column;align-items:flex-end;gap:6px;}
  #pauseBtn{
    width:38px;height:38px;padding-left:2px;border-radius:12px;border:1px solid rgba(120,180,240,.22);
    background:rgba(18,32,58,.55);color:#a9cbe8;font-size:12px;line-height:1;
    display:flex;align-items:center;justify-content:center;pointer-events:auto;
    backdrop-filter:blur(6px);transition:transform .12s;font-family:inherit;
  }
  #pauseBtn:active{transform:scale(.9);}
  #hud.hide #pauseBtn{pointer-events:none;}
  #endLayer{background:radial-gradient(85% 65% at 50% 32%,#0b1426 0%,#03050c 78%);backdrop-filter:none;}
  .pause-panel{max-width:300px;}

  /* ---------- 通用层 ---------- */
  .layer{
    position:absolute;inset:0;display:flex;align-items:center;justify-content:center;
    padding:26px;background:rgba(3,5,12,.92);backdrop-filter:blur(4px);z-index:5;
  }
  .layer.hide{display:none;}
  @keyframes fadeUp{from{opacity:0;transform:translateY(12px);}to{opacity:1;transform:none;}}

  /* ---------- 剧情层 ---------- */
  .story-box{width:100%;max-width:340px;text-align:left;}
  .story-ch{font-size:12px;letter-spacing:.42em;color:#5f8fbc;margin-bottom:8px;}
  .story-loc{
    font-size:23px;font-weight:800;color:#eaf7ff;letter-spacing:.08em;margin-bottom:22px;
    text-shadow:0 0 22px rgba(90,190,255,.6);
  }
  .story-lines p{
    font-size:13.5px;line-height:2.05;color:#a9c4de;margin-bottom:10px;opacity:0;
    animation:fadeUp .8s cubic-bezier(.2,.8,.3,1) forwards;
  }
  .story-lines p em{color:#7fd4ff;font-style:normal;font-weight:600;}
  .story-lines p b{color:#ffd166;font-weight:700;}
  .story-btn{margin-top:18px;opacity:0;animation:fadeUp .8s ease forwards;}

  /* ---------- 过场动画文字 ---------- */
  #cineLayer{background:none;backdrop-filter:none;pointer-events:none;}
  .cine-text{
    position:absolute;left:0;right:0;top:37%;text-align:center;
    font-size:29px;font-weight:900;letter-spacing:.16em;color:#eaf7ff;
    text-shadow:0 0 30px rgba(120,200,255,.85);opacity:0;transition:opacity .5s;padding-left:.16em;
  }
  .cine-text.show{opacity:1;}
  .cine-sub{
    position:absolute;left:0;right:0;top:calc(37% + 46px);text-align:center;
    font-size:12.5px;letter-spacing:.34em;color:#8fb4d6;opacity:0;transition:opacity .5s;padding-left:.34em;
  }
  .cine-sub.show{opacity:1;}

  /* ---------- 结局层 ---------- */
  .end-box{width:100%;max-width:352px;max-height:100%;overflow-y:auto;text-align:left;-webkit-overflow-scrolling:touch;}
  .end-lines p{
    font-size:13.5px;line-height:2.1;color:#a9c4de;margin-bottom:11px;opacity:0;
    animation:fadeUp .9s ease forwards;
  }
  .end-lines p.hl{color:#ffd166;font-weight:700;}
  .end-lines p.warn{color:#ff6b8a;font-weight:700;}
  .end-lines p.dim{color:#7e9cbb;}
  .end-lines p.big{
    font-size:19px;font-weight:800;color:#eaf7ff;letter-spacing:.22em;text-align:center;
    margin:24px 0;text-shadow:0 0 24px rgba(120,200,255,.7);
  }
  .end-lines p.quote{color:#bfe6ff;font-style:italic;border-left:2px solid rgba(110,180,255,.4);padding-left:12px;}
</style>
</head>
<body>
<div id="stage">
  <canvas id="game"></canvas>

  <div id="hud" class="hide">
    <div class="hud-bar">
      <div class="score-wrap">
        <div class="lbl">Score</div>
        <div class="val" id="scoreVal">0</div>
        <div class="sub" id="stageSub">第 1 关 · 1 / 10 波</div>
      </div>
      <div class="mid">
        <div class="lbl">Life</div>
        <div class="hearts" id="hearts"></div>
        <div class="power-track" id="powerTrack" style="justify-content:center;margin-top:5px"><i></i><i></i><i></i><i></i></div>
      </div>
      <div class="pause-wrap">
        <button id="pauseBtn" aria-label="暂停">❚❚</button>
        <div class="sub" id="bestSub" style="text-align:right">最高 0</div>
      </div>
    </div>
    <div class="prog"><div class="prog-fill" id="progFill"></div></div>
  </div>

  <div id="banner">
    <div class="b-title" id="bannerTitle">STAGE 1</div>
    <div class="b-sub" id="bannerSub">深空回廊</div>
  </div>

  <button id="muteBtn" aria-label="声音开关">🔊</button>

  <div id="cineLayer" class="layer hide">
    <div class="cine-text" id="cineText"></div>
    <div class="cine-sub" id="cineSub"></div>
  </div>

  <div id="storyLayer" class="layer hide">
    <div class="story-box">
      <div class="story-ch" id="storyCh"></div>
      <div class="story-loc" id="storyLoc"></div>
      <div class="story-lines" id="storyLines"></div>
      <button class="btn story-btn" id="storyBtn">进 入 战 区</button>
    </div>
  </div>

  <div id="pauseLayer" class="layer hide">
    <div class="panel pause-panel">
      <div class="over-title" style="color:#7fd4ff;text-shadow:0 0 2""",
    """0px rgba(90,190,255,.5)">已 暂 停</div>
      <div class="best" id="pauseInfo">第 1 关 · 1 / 10 波</div>
      <button class="btn" id="btnResume" style="margin-top:16px;">继 续 战 斗</button>
      <button class="btn ghost" id="btnRestart">重 新 开 始</button>
      <button class="btn ghost" id="btnQuit">返 回 主 菜 单</button>
    </div>
  </div>

  <div id="endLayer" class="layer hide">
    <div class="end-box">
      <div class="end-lines" id="endLines"></div>
      <button class="btn" id="btnEndRetry" style="margin-top:22px;">重 新 开 始</button>
    </div>
  </div>

  <div id="overlay">
    <div class="panel" id="panelMenu">
      <div class="title">星际战机</div>
      <div class="sub">10 大关卡 · 远征外星母巢</div>
      <div class="tips">
        <div><span class="k">✈</span> 按住屏幕任意处拖动即可操控战机</div>
        <div><span class="k">◈</span> 每关 10 个波次，打满配额即推进</div>
        <div><span class="b">☠</span> 每关最后是外星母舰决战</div>
        <div><span class="p">P</span> 拾取提升火力（最高 4 级）</div>
        <div><span class="h">♥</span> 拾取回复 1 点生命</div>
        <div><span class="k">❚❚</span> 右上角可随时暂停 / 重新开始</div>
      </div>
      <button class="btn" id="btnStart">开 始 远 征</button>
      <div class="best" id="menuBest">最高 0 分 · 第 0 关</div>
    </div>

    <div class="panel hide" id="panelOver">
      <div class="over-title">战 机 损 毁</div>
      <div class="final" id="finalScore">0</div>
      <div class="stage-reach">抵达第 <b id="finalStage">1</b> 关</div>
      <div class="new-record" id="newRecord" style="display:none;">✦ 新 纪 录 ✦</div>
      <div class="best" id="overBest">最高 0 分</div>
      <button class="btn" id="btnRetry" style="margin-top:18px;">再 战 一 次</button>
      <button class="btn ghost" id="btnMenu">返 回 主 菜 单</button>
    </div>

  </div>
</div>

<script>
(function () {
  'use strict';

  /* ================= 基础 ================= */
  var stage = document.getElementById('stage');
  var canvas = document.getElementById('game');
  var ctx = canvas.getContext('2d');
  var $ = function (id) { return document.getElementById(id); };
  var W = 0, H = 0, DPR = 1;
  var clamp = function (v, a, b) { return v < a ? a : (v > b ? b : v); };
  var rand = function (a, b) { return a + Math.random() * (b - a); };
  var PI2 = Math.PI * 2;

  var CHAPTERS = 10;
  var WAVES = 10;

  /* ================= 音效 ================= */
  var actx = null, muted = false;
  function initAudio() {
    if (actx) { if (actx.state === 'suspended') actx.resume(); return; }
    try { actx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { actx = null; }
  }
  function sfx(kind) {
    if (!actx || muted) return;
    var t = actx.currentTime, o = actx.createOscillator(), g = actx.createGain();
    o.connect(g); g.connect(actx.destination);
    var P = {
      shoot: ['square', 900, 560, 0.014, 0.055],
      boom:  ['sawtooth', 210, 45, 0.070, 0.26],
      hurt:  ['square', 150, 55, 0.105, 0.28],
      power: ['triangle', 540, 1180, 0.065, 0.15],
      boss:  ['sawtooth', 90, 30, 0.16, 0.9],
      clear: ['triangle', 620, 1400, 0.08, 0.4]
    }[kind];
    if (!P) return;
    o.type = P[0];
    o.frequency.setValueAtTime(P[1], t);
    o.frequency.exponentialRampToValueAtTime(P[2], t + P[4]);
    g.gain.setValueAtTime(P[3], t);
    g.gain.exponentialRampToValueAtTime(0.0001, t + P[4] + 0.04);
    o.start(t); o.stop(t + P[4] + 0.06);
  }

  /* ================= 星区主题（每 10 关一个） ================= */
  var ZONES = [
    { name: '深空回廊', bg: ['#070d1e', '#0a0618'], neb: [58, 123, 255], star: '#cfe9ff' },
    { name: '炽焰星云', bg: ['#1a0a12', '#0a0610'], neb: [255, 93, 122], star: '#ffd9e0' },
    { name: '翡翠星海', bg: ['#04160f', '#040d0a'], neb: [60, 232, 160], star: '#d6ffe9' },
    { name: '紫晶漩涡', bg: ['#120a1e', '#08060f'], neb: [160, 107, 255], star: '#e6d9ff' },
    { name: '黄金荒原', bg: ['#1a1206', '#0f0a04'], neb: [255, 176, 58], star: '#ffeccc' },
    { name: '冰封环带', bg: ['#061421', '#04101a'], neb: [95, 216, 255], star: '#d9f4ff' },
    { name: '血月裂谷', bg: ['#1c0810', '#0d0408'], neb: [255, 58, 94], star: '#ffccd6' },
    { name: '暗物质深渊', bg: ['#0a0a12', '#050508'], neb: [111, 123, 255], star: '#dfe3ff' },
    { name: '冥河之眼', bg: ['#0d0a1c', '#05040c'], neb: [122, 58, 255], star: '#d6ccff' },
    { name: '终焉之门', bg: ['#180a1c', '#0a040f'], neb: [255, 79, 216], star: '#ffd6f6' }
  ];

  /* ================= 外星飞船图鉴 ================= */
  /* move: straight | sine | zigzag | dive | hover
     atk : none | aimed | spread3 | spread5 | ring | spiral  */
  var SHIPS = {
    scout:  { r: 13, hp: 1,  speed: 185, score: 12, move: 'sine',     atk: 'none',    cd: 0,   color: '#7fe8ff', dark: '#137a9a', glow: '#5fe6ff' },
    dart:   { r: 15, hp: 2,  speed: 240, score: 20, move: 'dive',     atk: 'none',    cd: 0,   color: '#c9ffb0', dark: '#2d7a2a', glow: '#8dff7a' },
    saucer: { r: 20, hp: 4,  speed: 100, score: 34, move: 'sine',     atk: 'aimed',   cd: 1.9, color: '#d8d2ff', dark: '#4a3f9e', glow: '#a99bff' },
    eye:    { r: 19, hp: 6,  speed: 82,  score: 42, move: 'hover',    atk: 'spread3', cd: 2.1, color: '#ffc9dd', dark: '#8e2450', glow: '#ff6fa8' },
    hex:    { r: 23, hp: 8,  speed: 74,  score: 52, move: 'straight', atk: 'ring',    cd: 2.6, color: '#ffe2a8', dark: '#93601a', glow: '#ffb43a' },
    orb:    { r: 18, hp: 7,  speed: 96,  score: 46, move: 'hover',    atk: 'spread5', cd: 2.9, color: '#b9f5d8', dark: '#1b6b52', glow: '#4df0b0' },
    crab:   { r: 25, hp: 11, speed: 68,  score: 66, move: 'zigzag',   atk: 'aimed',   cd: 1.4, color: '#ffc4a0', dark: '#8e3a15', glow: '#ff8a4d' }
  };

  /* ================= 关卡配置 ================= */
  function levelCfg(n) {
    var boss = (n % 10 === 0);
    var quota = boss ? 1 : Math.round(5 + n * 0.22);
    var interval = Math.max(0.28, 1.0 - n * 0.0068);
    var hpMul = 1 + (n - 1) * 0.015;
    var spdMul = Math.min(1.55, 1 + (n - 1) * 0.0055);
    var types, weights;
    if (n <= 8)       { types = ['scout', 'dart']; }
    else if (n <= 18) { types = ['scout', 'dart', 'saucer']; }
    else if (n <= 30) { types = [""",
    """'scout', 'dart', 'saucer', 'eye']; }
    else if (n <= 45) { types = ['dart', 'saucer', 'eye', 'hex']; }
    else if (n <= 60) { types = ['saucer', 'eye', 'hex', 'orb']; }
    else              { types = ['eye', 'hex', 'orb', 'crab']; }
    var base = { scout: 60, dart: 46, saucer: 40, eye: 34, hex: 30, orb: 26, crab: 22 };
    weights = types.map(function (t) { return base[t]; });
    return {
      boss: boss, quota: quota, interval: interval, hpMul: hpMul, spdMul: spdMul,
      types: types, weights: weights, zone: Math.floor((n - 1) / 10)
    };
  }

  function bossCfg(n) {
    var k = n / 10;
    return {
      hp: Math.round(70 + k * 40 + (n - 1) * 1.6),
      speed: 46 + k * 5,
      score: 400 + n * 25
    };
  }

  /* ================= 状态 ================= */
  var state = {
    mode: 'menu',            // menu | story | playing | clear | cinematic | over | win
    paused: false,
    score: 0,
    chapter: 1,
    wave: 1,
    kills: 0,
    intro: 0,
    clearT: 0,
    cineT: 0,
    cineDone: false,
    cineTexted: false,
    storyC: 1,
    shake: 0,
    time: 0,
    spawnT: 1,
    shootT: 0,
    bossWarn: 0,
    best: parseInt(localStorage.getItem('starplane_best') || '0', 10) || 0,
    bestStage: parseInt(localStorage.getItem('starplane_stage') || '0', 10) || 0
  };

  function stageNo() { return (state.chapter - 1) * WAVES + state.wave; }
  /* ================= 剧情 ================= */
  var STORY = [
    { ch: '第一章', loc: '近地轨道 · 起航', lines: [
      '信号来自银河的另一端，重复了整整三十年。',
      '没有语言，没有图像，只有一个坐标，<em>和一句无法破译的问候</em>。',
      '人类把能凑出的全部战舰编成一支舰队，交给了一个人。',
      '—— 那个人是你。'
    ]},
    { ch: '第二章', loc: '碎石带 · 前哨', lines: [
      '舰队穿过小行星带时，雷达上亮起一座搁浅的巨构。',
      '它已经死了很久，骨架被尘埃磨得发白。',
      '可当你的战机掠过它的舷侧时，',
      '那些<b>早已熄灭的炮台，一盏一盏地睁开了眼</b>。'
    ]},
    { ch: '第三章', loc: '蜂巢星域 · 虫群', lines: [
      '这里没有星球，只有一层叠着一层的活体结构。',
      '它们像潮水一样从裂缝里涌出来，',
      '没有队形，没有指挥，<em>只是单纯地多</em>。',
      '舰队第一次开始计算：我们还能撑多久。'
    ]},
    { ch: '第四章', loc: '暗物质深渊 · 失灵', lines: [
      '跃迁出错的第七秒，所有仪表同时归零。',
      '导航死了，通讯死了，只有窗外的黑暗还活着。',
      '你关掉自动驾驶，把操纵杆握进掌心。',
      '—— 这一次，靠你自己飞出去。'
    ]},
    { ch: '第五章', loc: '母舰残骸 · 密码', lines: [
      '第一艘外星母舰在爆炸中裂成两半。',
      '残骸里，你的通讯器被动截获了一段持续广播的代码。',
      '解码之后，所有人都沉默了。',
      '那串代码，是<b>你的名字，和你的坐标</b>。'
    ]},
    { ch: '第六章', loc: '血月裂谷 · 代价', lines: [
      '舰队折损过半，旗舰的引擎只剩下三个。',
      '血色的光从裂谷深处涌上来，照亮了所有还活着的人。',
      '没有人提出返航。',
      '你听见自己在广播里说：<em>再往前一点</em>。'
    ]},
    { ch: '第七章', loc: '人造虫洞 · 时差', lines: [
      '那不是自然形成的。有人在这条航线上，凿了一扇门。',
      '穿过它的瞬间，时间开始错乱。',
      '你看见自己的舰队在远处燃烧，',
      '而通讯频道里，传来<b>你自己三小时后的声音</b>。'
    ]},
    { ch: '第八章', loc: '星门 · 低语', lines: [
      '星门比行星还大，安静地悬在虚无里。',
      '舰队接入它的能量网络，第一次听见了"声音"。',
      '不是语言，是某种更古老的东西，',
      '在反复地说着<b>同一句</b>，耐心得像在等待什么。'
    ]},
    { ch: '第九章', loc: '母巢核心 · 倾巢', lines: [
      '这里已经不需要导航了 —— 所有路线都通向同一个地方。',
      '守卫倾巢而出，它们不再后退。',
      '母巢之心在正前方跳动，像一颗被金属包裹的心脏。',
      '终点，就在那里。'
    ]},
    { ch: '第十章', loc: '终焉 · 母巢之脑', lines: [
      '母巢之脑就在你面前，庞大得不像一件兵器。',
      '它没有攻击你，只是<b>安静地看着你飞过来</b>。',
      '你按下发射键的那一秒，',
      '忽然意识到：<em>它一直在等你</em>。'
    ]}
  ];

  /* ================= 结局（悬念） ================= */
  var ENDING = [
    { c: '', t: '母巢之脑在光中崩解。' },
    { c: '', t: '所有敌舰同时静止，随即失去动力，缓缓坠落。' },
    { c: 'dim', t: '你松开操纵杆，第一次觉得呼吸这么吵。' },
    { c: 'big', t: '—— 结束了 ——' },
    { c: '', t: '然后是残骸。火光。慢慢飘散的金属碎片。' },
    { c: '', t: '你准备返航的时候，眼角扫到了什么。' },
    { c: 'hl', t: '母巢残骸的正中央，亮了起来。' },
    { c: '', t: '那不是爆炸。' },
    { c: 'hl', t: '那是一道门。' },
    { c: '', t: '门缓缓地向两侧打开。' },
    { c: 'warn', t: '门的那一边，是数以万计、看不到尽头的舰队。' },
    { c: '', t: '它们一架架亮起引擎，安静地，朝着门这边转过头来。' },
    { c: '', t: '通讯器里，传来一个你从未听过的声音。' },
    { c: '', t: '它用你的母语，一字一句，清晰地说：' },
    { c: 'quote', t: '「欢迎回来。」' },
    { c: 'big', t: '待 续' }
  ];

  var cfg = levelCfg(1);
  var zone = ZONES[0];

  var player = {
    x: 0, y: 0, tx: 0, ty: 0, r: 15,
    hp: 3, maxHp: 5, power: 1, inv: 0, alive: true, roll: 0
  };

  var bullets = [], ebullets = [], enemies = [], parts = [], items = [], stars = [], nebs = [];
  var bossObj = null;
  var inited = false;

  /* ================= 尺寸 & 背景 ================= */
  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, 2);
    var r = canvas.getBoundingClientRect();
    W = Math.max(1, Math.round(r.width));
    H = Math.max(1, Math.round(r.height));
    canvas.width = Math.round(W * DPR);
    canvas.height = Math.round(H * DPR);
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    if (!inited) {
      player.x = player.tx = W / 2;
      player.y = player.ty = H - 150;
      inited = true;
    }
    player.tx = clamp(player.tx, 22, W - 22);
    player.ty = clamp(player.ty, 60, H - 30);
    player.x = clamp(player.x, 14, W - 14);
    player.y = clamp(player.y, 40, H - 20);
    initSky();
  }

  function initSky() {
    stars = [];
    var n = Math.round(W * H / 4600);
    for (var i = 0; i < n; i++) {
      var z = Math.pow(Math.random(), 1.5);
      stars.push({ x: Math.random() * W, y: Math.random() * H, z: z, s: 0.5 + z * 1.9, v: 26 + z * 205 });
    }
    nebs = [];
    for (var j = 0; j < 3; j++) {
      nebs.push({
        x: Math.random() * W, y: Math.random() * H,
        r: rand(H * 0.22, H * 0.45), a: rand(0.05, 0.11),
        sx: rand(-6, 6), sy: rand(-16, -34)
      });
    }
  }

  /* ================= 输入 ================= */
  var activeId = null, lastX = 0, lastY = 0;
  var keys = { left: false, right: false, up: false, down: false };

  function clampTarget() {
    player.tx = clamp(player.tx, 22, W - 22);
    player.ty = clamp(player.ty, 60, H - 30);
  }

  canvas.addEventListener('pointerdown', function (e) {
    if (state.mode !== 'playing' || state.paused) return;
    if (activeId !== null) return;
    initAudio();
    activeId = e.pointerId; lastX = e.clientX; lastY = e.clientY;
    if (canvas.setPointerCapture) { try { canvas.setPointerCapture(e.pointerId); } catch (err) {} }
    e.preventDefault();
  }, { passive: false });

  canvas""",
    """.addEventListener('pointermove', function (e) {
    if (e.pointerId !== activeId || state.mode !== 'playing' || state.paused) return;
    var dx = e.clientX - lastX, dy = e.clientY - lastY;
    lastX = e.clientX; lastY = e.clientY;
    player.tx += dx * 1.35;
    player.ty += dy * 1.35;
    clampTarget();
    e.preventDefault();
  }, { passive: false });

  function endPointer(e) { if (e.pointerId === activeId) activeId = null; }
  canvas.addEventListener('pointerup', endPointer);
  canvas.addEventListener('pointercancel', endPointer);
  canvas.addEventListener('pointerleave', endPointer);

  window.addEventListener('keydown', function (e) {
    var k = e.key.toLowerCase();
    if (k === 'arrowleft' || k === 'a') keys.left = true;
    if (k === 'arrowright' || k === 'd') keys.right = true;
    if (k === 'arrowup' || k === 'w') keys.up = true;
    if (k === 'arrowdown' || k === 's') keys.down = true;
    if (k === ' ' || k === 'escape') {
      if (k === 'escape' || state.mode === 'playing' || state.mode === 'clear') {
        e.preventDefault();
        if (state.mode === 'story') { closeStory(); }
        else if (state.mode === 'menu' || state.mode === 'over' || state.mode === 'win') { startGame(); }
        else if (state.paused) { resumeGame(); } else { pauseGame(); }
      }
    }
  });
  window.addEventListener('keyup', function (e) {
    var k = e.key.toLowerCase();
    if (k === 'arrowleft' || k === 'a') keys.left = false;
    if (k === 'arrowright' || k === 'd') keys.right = false;
    if (k === 'arrowup' || k === 'w') keys.up = false;
    if (k === 'arrowdown' || k === 's') keys.down = false;
  });

  /* ================= 粒子 ================= */
  function boom(x, y, color, count, speed) {
    for (var i = 0; i < count; i++) {
      var a = Math.random() * PI2, s = speed * rand(0.2, 1), life = rand(0.35, 0.9);
      parts.push({
        x: x, y: y, vx: Math.cos(a) * s, vy: Math.sin(a) * s,
        life: life, max: life, r: rand(1.3, 3.8), color: color
      });
    }
  }
  function ring(x, y, color, r0, speed) {
    parts.push({ ring: true, x: x, y: y, r: r0, vr: speed, life: 0.45, max: 0.45, color: color, vx: 0, vy: 0 });
  }

  /* ================= 生成敌机（每波随机编排） ================= */
  var plan = { main: 'scout', second: 'scout', pattern: 'single', rate: 1, alt: 0.3 };

  function rollPattern() {
    var r = Math.random();
    if (r < 0.30) return 'single';
    if (r < 0.53) return 'pair';
    if (r < 0.72) return 'line';
    if (r < 0.88) return 'vee';
    return 'burst';
  }

  function newWavePlan() {
    var pool = cfg.types;
    return {
      main: pool[(Math.random() * pool.length) | 0],
      second: pool[(Math.random() * pool.length) | 0],
      pattern: rollPattern(),
      rate: rand(0.72, 1.42),      /* 这一波的出怪节奏倍率 */
      alt: rand(0.12, 0.45)        /* 混入其它机种的比例 */
    };
  }

  function pickTypeForWave() {
    var r = Math.random(), pool = cfg.types;
    if (r < 0.42) return plan.main;
    if (r < 0.70) return plan.second;
    return pool[(Math.random() * pool.length) | 0];
  }

  function spawnAt(type, x, yOff) {
    var S = SHIPS[type], r = S.r;
    x = clamp(x, r + 10, W - r - 10);
    var hp = Math.max(1, Math.round(S.hp * cfg.hpMul));
    enemies.push({
      type: type, x: x, y: -r - 12 - (yOff || 0), x0: x, seed: Math.random() * PI2, r: r,
      hp: hp, maxHp: hp, vy: S.speed * cfg.spdMul * rand(0.88, 1.24),
      t: 0, fireT: S.cd ? S.cd * rand(0.45, 0.95) : 0, hit: 0,
      hoverY: S.move === 'hover' ? rand(H * 0.26, H * 0.52) : 0,
      hoverDur: rand(4.5, 7.5),
      dir: Math.random() < 0.5 ? -1 : 1, phase: 'in'
    });
  }

  function spawnWave() {
    if (enemies.length >= 34) return;
    /* 一波之内也会临时换编队，避免单调 */
    if (Math.random() < 0.28) plan.pattern = rollPattern();
    var p = plan.pattern, cx, i, n;
    if (p === 'pair') {
      cx = rand(64, W - 64);
      spawnAt(pickTypeForWave(), cx - 40, 0);
      spawnAt(pickTypeForWave(), cx + 40, 42);
    } else if (p === 'line') {
      cx = rand(92, W - 92);
      for (i = -1; i <= 1; i++) spawnAt(pickTypeForWave(), cx + i * 64, Math.abs(i) * 26);
    } else if (p === 'vee') {
      cx = rand(92, W - 92);
      spawnAt(pickTypeForWave(), cx, 0);
      spawnAt(pickTypeForWave(), cx - 58, 48);
      spawnAt(pickTypeForWave(), cx + 58, 48);
    } else if (p === 'burst') {
      n = 3 + ((Math.random() * 3) | 0);
      for (i = 0; i < n; i++) spawnAt(pickTypeForWave(), rand(30, W - 30), i * 30);
    } else {
      spawnAt(pickTypeForWave(), rand(30, W - 30), 0);
    }
  }

  function spawnBoss() {
    var b = bossCfg(stageNo());
    bossObj = {
      x: W / 2, y: -110, r: 78, hp: b.hp, maxHp: b.hp, score: b.score,
      speed: b.speed, t: 0, dir: 1, hit: 0, entering: true,
      atk: 0, atkT: 2.2, spiralA: 0, pulse: 0,
      parts: [{ dx: 0, dy: 0, r: 42 }, { dx: -56, dy: 6, r: 26 }, { dx: 56, dy: 6, r: 26 },
              { dx: -26, dy: -22, r: 20 }, { dx: 26, dy: -22, r: 20 }]
    };
    sfx('boss');
    state.shake = 1.2;
  }

  function enemyFire(e) {
    var S = SHIPS[e.type], sp, a;
    if (S.atk === 'aimed') {
      var dx = player.x - e.x, dy = player.y - e.y, d = Math.sqrt(dx * dx + dy * dy) || 1;
      sp = 210 * cfg.spdMul;
      ebullets.push({ x: e.x, y: e.y + e.r * 0.6, vx: dx / d * sp, vy: dy / d * sp, r: 5 });
    } else if (S.atk === 'spread3') {
      sp = 195 * cfg.spdMul;
      for (a = -1; a <= 1; a++) {
        ebullets.push({ x: e.x, y: e.y + e.r * 0.5, vx: Math.sin(a * 0.34) * sp, vy: Math.cos(a * 0.34) * sp, r: 6 });
      }
    } else if (S.atk === 'spread5') {
      sp = 175 * cfg.spdMul;
      for (a = -2; a <= 2; a++) {
        var ang = a * 0.26;
        ebullets.push({ x: e.x, y: e.y + e.r * 0.4, vx: Math.sin(ang) * sp, vy: Math.cos(ang) * sp, r: 5.5 });
      }
    } else if (S.atk === 'ring') {
      sp = 155 * cfg.spdMul;
      for (a = 0; a < 10; a++) {
        var ag = a / 10 * PI2 + e.t;
        ebullets.push({ x: e.x, y: e.y, vx: Math.cos(ag) * sp, vy: Math.sin(ag) *""",
    """ sp, r: 6 });
      }
    }
  }

  function bossFire(b) {
    var mode = b.atk % 3;
    var lvl = 1 + (stageNo() / 100) * 0.6;
    var sp = 175 * lvl, i, a, ag;
    if (mode === 0) {           /* 瞄准三连 */
      for (i = 0; i < 3; i++) {
        var dx = player.x - b.x, dy = player.y - b.y, d = Math.sqrt(dx * dx + dy * dy) || 1;
        var off = (i - 1) * 0.20;
        var ca = Math.cos(off), sa = Math.sin(off);
        var vx = (dx / d) * ca - (dy / d) * sa, vy = (dx / d) * sa + (dy / d) * ca;
        ebullets.push({ x: b.x, y: b.y + 34, vx: vx * sp, vy: vy * sp, r: 7 });
      }
    } else if (mode === 1) {    /* 环形弹幕 */
      var cnt = 14 + Math.floor(stageNo() / 12);
      for (a = 0; a < cnt; a++) {
        ag = a / cnt * PI2 + b.t * 0.8;
        ebullets.push({ x: b.x, y: b.y, vx: Math.cos(ag) * sp * 0.85, vy: Math.sin(ag) * sp * 0.85, r: 6.5 });
      }
    } else {                    /* 双螺旋 */
      for (i = 0; i < 2; i++) {
        ag = b.spiralA + i * Math.PI;
        ebullets.push({ x: b.x + Math.cos(ag) * 40, y: b.y + 20, vx: Math.cos(ag) * sp, vy: Math.sin(ag) * sp * 0.8 + 60, r: 6 });
      }
      b.spiralA += 0.42;
    }
    sfx('shoot');
  }

  /* ================= 玩家射击 ================= */
  function fire() {
    var p = player.power, spd = -980, x = player.x, y = player.y;
    function add(bx, by, vx, vy, dmg, r) { bullets.push({ x: bx, y: by, vx: vx, vy: vy, dmg: dmg, r: r, life: 2.6, t: 0 }); }
    if (p === 1) {
      add(x, y - 24, 0, spd, 1, 3);
    } else if (p === 2) {
      add(x - 8, y - 18, 0, spd, 1, 3); add(x + 8, y - 18, 0, spd, 1, 3);
    } else if (p === 3) {
      add(x, y - 26, 0, spd, 2, 4);
      add(x - 13, y - 10, -155, spd * 0.96, 1, 3);
      add(x + 13, y - 10, 155, spd * 0.96, 1, 3);
    } else {
      add(x - 5, y - 26, 0, spd, 2, 4); add(x + 5, y - 26, 0, spd, 2, 4);
      add(x - 15, y - 8, -205, spd * 0.92, 1, 3); add(x + 15, y - 8, 205, spd * 0.92, 1, 3);
    }
    sfx('shoot');
  }

  /* ================= 伤害 ================= */
  function hurtPlayer() {
    if (player.inv > 0 || !player.alive) return;
    player.hp--;
    player.inv = 2.0;
    player.power = Math.max(1, player.power - 1);
    state.shake = 1;
    boom(player.x, player.y, '#8fdcff', 22, 240);
    ring(player.x, player.y, '#8fdcff', 8, 320);
    sfx('hurt');
    updateHUD();
    if (player.hp <= 0) {
      player.alive = false;
      boom(player.x, player.y, '#ffffff', 46, 420);
      boom(player.x, player.y, '#ff9a5c', 34, 300);
      ring(player.x, player.y, '#ffffff', 10, 520);
      sfx('boom');
      gameOver();
    }
  }

  function killEnemy(e) {
    state.score += SHIPS[e.type].score;
    state.kills++;
    boom(e.x, e.y, SHIPS[e.type].glow, e.type === 'crab' || e.type === 'hex' ? 26 : 16, 240);
    ring(e.x, e.y, SHIPS[e.type].glow, e.r * 0.5, 300);
    sfx('boom');
    if (e.type === 'crab' || e.type === 'hex' || e.type === 'orb' || (e.type === 'eye' && Math.random() < 0.3)) {
      var kind = Math.random() < 0.26 ? 'life' : 'power';
      items.push({ x: e.x, y: e.y, vy: 95, t: 0, type: kind });
    }
    updateHUD();
  }

  /* ================= 关卡流程 ================= */
  function showBanner(title, sub, warn) {
    var b = $('banner');
    $('bannerTitle').textContent = title;
    $('bannerTitle').className = 'b-title' + (warn ? ' warn' : '');
    $('bannerSub').textContent = sub || '';
    b.classList.add('show');
    clearTimeout(showBanner._t);
    showBanner._t = setTimeout(function () { b.classList.remove('show'); }, 1600);
  }

  function beginChapter(c) {
    state.chapter = c;
    state.wave = 1;
    initSky();
    bullets = []; ebullets = []; enemies = [];
    bossObj = null;
    startWave(true);
  }

  function onWaveCleared() {
    if (state.wave < WAVES) {
      state.wave++;
      startWave(false);        /* 不清场、不中断，难度直接抬升 */
    } else {
      chapterClear();
    }
  }

  function startWave(isFirst) {
    cfg = levelCfg(stageNo());
    zone = ZONES[state.chapter - 1] || ZONES[0];
    plan = newWavePlan();
    state.kills = 0;
    state.clearT = 0;
    state.intro = 0;
    state.bossWarn = (state.wave === WAVES) ? 2.0 : 0;
    state.spawnT = isFirst ? 0.7 : rand(0.15, 0.55);
    state.mode = 'playing';
    state.paused = false;
    hideAllLayers();
    $('overlay').classList.add('hide');
    $('hud').classList.remove('hide');
    if (isFirst) {
      player.x = player.tx = W / 2;
      player.y = player.ty = H - 150;
      player.inv = Math.max(player.inv, 1.6);
      showBanner('第 ' + state.chapter + ' 关', zone.name);
    }
    updateHUD();
  }

  function chapterClear() {
    state.mode = 'clear';
    state.clearT = 1.5;
    var bonus = state.chapter * 120;
    state.score += bonus;
    for (var i = 0; i < enemies.length; i++) boom(enemies[i].x, enemies[i].y, SHIPS[enemies[i].type].glow, 12, 220);
    enemies = []; ebullets = [];
    if (player.hp < player.maxHp) player.hp++;
    if (player.power < 4) player.power++;
    sfx('clear');
    showBanner('CLEAR', '第 ' + state.chapter + ' 关 通过  +' + bonus, false);
    updateHUD();
  }

  /* ---------- 过场动画 ---------- */
  var cineParts = [], cineMax = 700;
  function initCine() {
    cineParts = [];
    cineMax = Math.sqrt(W * W + H * H) * 0.72;
    for (var i = 0; i < 170; i++) {
      cineParts.push({ a: Math.random() * PI2, r: rand(4, cineMax), v: rand(70, 300), w: rand(1, 2.6) });
    }
  }
  function startCinematic() {
    state.mode = 'cinematic';
    state.cineT = 0;
    state.cineDone = false;
    state.cineTexted = false;
    state.paused = false;
    initCine();
    $('banner').classList.remove('show');
    $('hud').classList.add('hide');
    $('cineText').textContent = '第 ' + state.chapter + ' 关 通过';
    $('cineSub').textContent = (state.chapter >= CHAPTERS) ? '母巢之心 · 正在碎裂' : ('跃迁前往 · ' + (ZONES[state.chapter] ? ZONES[state.chapter].name : '未知星域'));
    $('cineLayer').classList.remove('hide');
    $('cineText').classList.remove('show');
    $('cineSub').classList.remove('show');
  }
  functio""",
    """n updateCinematic(dt) {
    state.cineT += dt;
    for (var i = 0; i < cineParts.length; i++) {
      var p = cineParts[i];
      p.r += p.v * dt * (0.35 + state.cineT * 1.05);
      if (p.r > cineMax) { p.r = rand(3, 60); p.a = Math.random() * PI2; }
    }
    if (!state.cineTexted && state.cineT > 1.0) {
      state.cineTexted = true;
      $('cineText').classList.add('show');
      $('cineSub').classList.add('show');
    }
    if (!state.cineDone && state.cineT > 5.0) {
      state.cineDone = true;
      $('cineText').classList.remove('show');
      $('cineSub').classList.remove('show');
      if (state.chapter >= CHAPTERS) ending();
      else showStory(state.chapter + 1);
    }
  }

  /* ---------- 剧情 ---------- */
  function showStory(c) {
    state.mode = 'story';
    state.storyC = c;
    state.paused = false;
    hideAllLayers();
    $('hud').classList.add('hide');
    $('overlay').classList.add('hide');
    var d = STORY[c - 1];
    $('storyCh').textContent = d.ch + ' · 第 ' + c + ' 关';
    $('storyLoc').textContent = d.loc;
    var html = '';
    for (var i = 0; i < d.lines.length; i++) {
      html += '<p style="animation-delay:' + (0.35 + i * 0.5).toFixed(2) + 's">' + d.lines[i] + '</p>';
    }
    $('storyLines').innerHTML = html;
    var btn = $('storyBtn');
    btn.style.animation = 'none';
    void btn.offsetWidth;
    btn.style.animation = 'fadeUp .8s ease forwards';
    btn.style.animationDelay = (0.35 + d.lines.length * 0.5 + 0.2).toFixed(2) + 's';
    $('storyLayer').classList.remove('hide');
  }

  function closeStory() {
    $('storyLayer').classList.add('hide');
    beginChapter(state.storyC);
  }

  /* ---------- 结局 ---------- */
  function ending() {
    state.mode = 'win';
    state.paused = false;
    if (state.score > state.best) state.best = state.score;
    state.bestStage = CHAPTERS;
    try {
      localStorage.setItem('starplane_best', String(state.best));
      localStorage.setItem('starplane_stage', String(CHAPTERS));
    } catch (e) {}
    $('menuBest').textContent = '最高 ' + state.best + ' 分 · 第 ' + state.bestStage + ' 关';
    hideAllLayers();
    $('hud').classList.add('hide');
    $('overlay').classList.add('hide');
    var html = '';
    for (var i = 0; i < ENDING.length; i++) {
      var e = ENDING[i];
      html += '<p class="' + e.c + '" style="animation-delay:' + (0.3 + i * 0.45).toFixed(2) + 's">' + e.t + '</p>';
    }
    $('endLines').innerHTML = html;
    var btn = $('btnEndRetry');
    btn.style.animation = 'none';
    void btn.offsetWidth;
    btn.style.animation = 'fadeUp .9s ease forwards';
    btn.style.animationDelay = '1.6s';
    $('endLayer').classList.remove('hide');
    $('endLayer').scrollTop = 0;
  }

  /* ---------- 层 / 暂停 ---------- */
  function hideAllLayers() {
    $('storyLayer').classList.add('hide');
    $('pauseLayer').classList.add('hide');
    $('cineLayer').classList.add('hide');
    $('endLayer').classList.add('hide');
  }

  function pauseGame() {
    if (state.mode !== 'playing' && state.mode !== 'clear') return;
    if (state.paused) return;
    state.paused = true;
    $('pauseInfo').textContent = '第 ' + state.chapter + ' 关 · ' + state.wave + ' / ' + WAVES + ' 波 · ' + state.score + ' 分';
    $('pauseLayer').classList.remove('hide');
  }

  function resumeGame() {
    state.paused = false;
    $('pauseLayer').classList.add('hide');
  }

  /* ---------- 开始 / 结束 ---------- */
  function startGame() {
    initAudio();
    player.hp = 3; player.power = 1; player.inv = 1.6; player.alive = true;
    state.score = 0;
    state.chapter = 1;
    state.wave = 1;
    state.shake = 0;
    state.paused = false;
    state.intro = 0;
    bullets = []; ebullets = []; enemies = []; parts = []; items = [];
    bossObj = null;
    cfg = levelCfg(1);
    zone = ZONES[0];
    $('overlay').classList.add('hide');
    showStory(1);
  }

  function gameOver() {
    state.mode = 'over';
    state.paused = false;
    var isNew = false;
    if (state.score > state.best) { state.best = state.score; isNew = true; }
    if (state.chapter > state.bestStage) { state.bestStage = state.chapter; isNew = true; }
    try {
      localStorage.setItem('starplane_best', String(state.best));
      localStorage.setItem('starplane_stage', String(state.bestStage));
    } catch (e) {}
    $('finalScore').textContent = state.score;
    $('finalStage').textContent = state.chapter;
    $('overBest').textContent = '最高 ' + state.best + ' 分 · 第 ' + state.bestStage + ' 关';
    $('menuBest').textContent = '最高 ' + state.best + ' 分 · 第 ' + state.bestStage + ' 关';
    $('newRecord').style.display = isNew && state.score > 0 ? 'block' : 'none';
    setTimeout(function () {
      $('panelMenu').classList.add('hide');
      $('panelOver').classList.remove('hide');
      $('overlay').classList.remove('hide');
    }, 750);
    setTimeout(function () { $('hud').classList.add('hide'); }, 550);
  }

  /* ================= 死亡线（Boss 血条画布上） ================= */

  /* ================= 更新 ================= */
  function update(dt) {
    state.time += dt;
    if (state.shake > 0) state.shake = Math.max(0, state.shake - dt * 2.6);
    if (player.inv > 0) player.inv = Math.max(0, player.inv - dt);
    if (state.bossWarn > 0) {
      state.bossWarn -= dt;
      if (state.bossWarn <= 0) spawnBoss();
    }

    /* 键盘 */
    var ks = 640 * dt;
    if (keys.left) player.tx -= ks;
    if (keys.right) player.tx += ks;
    if (keys.up) player.ty -= ks;
    if (keys.down) player.ty += ks;
    if (keys.left || keys.right || keys.up || keys.down) clampTarget();

    /* 平滑跟随 + 侧倾 */
    var k = 1 - Math.pow(0.00004, dt);
    var px0 = player.x;
    player.x += (player.tx - player.x) * k;
    player.y += (player.ty - player.y) * k;
    player.roll += ((player.x - px0) / Math.max(1, W) * 2.6 - player.roll) * Math.min(1, dt * 10);
    player.roll = clamp(player.roll, -0.5, 0.5);
    player.x = clamp(player.x, 12, W - 12);
    player.y = clamp(player.y, 36, H - 18);

    /* 自动射击 */
    if (player.aliv""",
    """e && state.mode !== 'over' && state.mode !== 'win') {
      state.shootT -= dt;
      if (state.shootT <= 0) { fire(); state.shootT = 0.12; }
    }

    /* 星空 / 星云 */
    var boost = 1 + Math.min(1.1, stageNo() * 0.012);
    for (var i = 0; i < stars.length; i++) {
      var st = stars[i];
      st.y += st.v * dt * boost;
      if (st.y > H + 2) { st.y = -2; st.x = Math.random() * W; }
    }
    for (i = 0; i < nebs.length; i++) {
      var nb = nebs[i];
      nb.x += nb.sx * dt; nb.y += nb.sy * dt;
      if (nb.y + nb.r < -20) { nb.y = H + nb.r * 0.6; nb.x = Math.random() * W; }
    }

    /* 敌机生成 */
    if (state.mode === 'playing' && state.intro > 0) {
      state.intro -= dt;
    } else if (state.mode === 'playing' && !bossObj && !cfg.boss) {
      state.spawnT -= dt;
      if (state.spawnT <= 0) {
        spawnWave();
        state.spawnT = cfg.interval * plan.rate * rand(0.72, 1.35);
      }
    }

    /* 玩家子弹 */
    for (i = bullets.length - 1; i >= 0; i--) {
      var b = bullets[i];
      b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt; b.t += dt;
      if (b.y < -30 || b.life <= 0) bullets.splice(i, 1);
    }
    /* 敌弹 */
    for (i = ebullets.length - 1; i >= 0; i--) {
      var eb = ebullets[i];
      eb.x += eb.vx * dt; eb.y += eb.vy * dt;
      if (eb.y > H + 30 || eb.y < -60 || eb.x < -50 || eb.x > W + 50) ebullets.splice(i, 1);
    }

    /* 敌机行为 */
    for (i = enemies.length - 1; i >= 0; i--) {
      var e = enemies[i];
      e.t += dt;
      if (e.hit > 0) e.hit -= dt;
      var mv = SHIPS[e.type].move;

      if (mv === 'sine') {
        e.y += e.vy * dt;
        e.x = e.x0 + Math.sin(e.t * 2.4 + e.seed) * (22 + e.r * 0.3);
      } else if (mv === 'zigzag') {
        e.y += e.vy * dt;
        e.x += e.dir * 92 * dt;
        if (e.x < e.r + 8) { e.x = e.r + 8; e.dir = 1; }
        if (e.x > W - e.r - 8) { e.x = W - e.r - 8; e.dir = -1; }
      } else if (mv === 'dive') {
        if (e.phase === 'in') {
          e.y += e.vy * dt;
          e.x += (player.x - e.x) * Math.min(1, dt * 0.9);
        } else { e.y += e.vy * dt; }
      } else if (mv === 'hover') {
        if (e.t > e.hoverDur) { e.y += e.vy * 1.15 * dt; }
        else if (e.y < e.hoverY) { e.y += e.vy * dt; }
        else { e.y += Math.sin(e.t * 1.6) * 12 * dt; e.x += Math.sin(e.t * 0.9 + e.seed) * 34 * dt; }
      } else {
        e.y += e.vy * dt;
      }
      e.x = clamp(e.x, e.r, W - e.r);

      var S = SHIPS[e.type];
      if (S.cd && e.y > 0 && (mv !== 'hover' || e.y >= e.hoverY - 6)) {
        e.fireT -= dt;
        if (e.fireT <= 0) { e.fireT = S.cd * rand(0.85, 1.15); enemyFire(e); }
      }

      /* 子弹 vs 敌机 */
      var dead = false;
      for (var j = bullets.length - 1; j >= 0; j--) {
        var bb = bullets[j];
        var ddx = bb.x - e.x, ddy = bb.y - e.y;
        if (ddx * ddx + ddy * ddy < (e.r + bb.r) * (e.r + bb.r)) {
          e.hp -= bb.dmg; e.hit = 0.09;
          boom(bb.x, bb.y, '#ffe9a8', 3, 90);
          bullets.splice(j, 1);
          if (e.hp <= 0) { killEnemy(e); enemies.splice(i, 1); dead = true; break; }
        }
      }
      if (dead) continue;

      /* 撞机 */
      var pdx = player.x - e.x, pdy = player.y - e.y, pr = e.r + player.r * 0.72;
      if (player.alive && pdx * pdx + pdy * pdy < pr * pr) {
        boom(e.x, e.y, SHIPS[e.type].glow, 14, 200);
        enemies.splice(i, 1);
        hurtPlayer();
        continue;
      }
      if (e.y - e.r > H + 20) enemies.splice(i, 1);
    }

    /* Boss 行为 */
    if (bossObj) updateBoss(dt);

    /* 敌弹 vs 玩家 */
    if (player.alive) {
      for (i = ebullets.length - 1; i >= 0; i--) {
        var eb2 = ebullets[i];
        var ex = eb2.x - player.x, ey = eb2.y - player.y;
        var rr = eb2.r + player.r * 0.6;
        if (ex * ex + ey * ey < rr * rr) {
          ebullets.splice(i, 1);
          if (player.inv <= 0) hurtPlayer();
        }
      }
    }

    /* 道具 */
    for (i = items.length - 1; i >= 0; i--) {
      var it = items[i];
      it.t += dt;
      it.y += it.vy * dt;
      it.x += Math.sin(it.t * 3) * 20 * dt;
      var idx = it.x - player.x, idy = it.y - player.y;
      if (player.alive && idx * idx + idy * idy < (player.r + 21) * (player.r + 21)) {
        if (it.type === 'power') {
          if (player.power < 4) player.power++; else state.score += 40;
        } else {
          if (player.hp < player.maxHp) player.hp++; else state.score += 40;
        }
        sfx('power');
        boom(it.x, it.y, it.type === 'power' ? '#ffd166' : '#ff5d8f', 12, 160);
        items.splice(i, 1);
        updateHUD();
        continue;
      }
      if (it.y > H + 30) items.splice(i, 1);
    }

    /* 粒子 */
    for (i = parts.length - 1; i >= 0; i--) {
      var p = parts[i];
      p.life -= dt;
      if (p.life <= 0) { parts.splice(i, 1); continue; }
      if (p.ring) { p.r += p.vr * dt; continue; }
      p.x += p.vx * dt; p.y += p.vy * dt;
      p.vx *= (1 - 2.2 * dt); p.vy *= (1 - 2.2 * dt);
      p.vy += 60 * dt;
    }

    /* 波次推进判定 */
    if (state.mode === 'playing' && state.intro <= 0 && !cfg.boss && state.kills >= cfg.quota) {
      onWaveCleared();
    } else if (state.mode === 'clear') {
      state.clearT -= dt;
      if (state.clearT <= 0) startCinematic();
    }
  }

  function updateBoss(dt) {
    var b = bossObj;
    b.t += dt;
    if (b.hit > 0) b.hit -= dt;
    b.pulse += dt;

    if (b.entering) {
      b.y += 130 * dt;
      if (b.y >= 108) { b.y = 108; b.entering = false; }
      return;
    }
    b.x += b.dir * b.speed * dt;
    if (b.x < b.r * 0.72) { b.x = b.r * 0.72; b.dir = 1; }
    if (b.x > W - b.r * 0.72) { b.x = W - b.r * 0.72; b.dir = -1; }
    b.y = 108 + Math.sin(b.t * 1.1) * 14;

    b.atkT -= dt;
    if (b.atkT <= 0) {
      b.atkT = Math.max(0.75, 1.5 - stageNo() * 0.005);
      b.atk++;
      bossFire(b);
      if (b.atk % 3 === 0) b.atkT += 0.5;
    }

    /* 子弹命中 */
    for (var j = bullets.length - 1; j >= 0; j--) {
      var bb = bullets[j], hitAny = false;
      for (var q = 0; q """,
    """< b.parts.length; q++) {
        var pt = b.parts[q];
        var dx = bb.x - (b.x + pt.dx), dy = bb.y - (b.y + pt.dy), rr = pt.r + bb.r;
        if (dx * dx + dy * dy < rr * rr) { hitAny = true; break; }
      }
      if (hitAny) {
        b.hp -= bb.dmg; b.hit = 0.08;
        boom(bb.x, bb.y, '#ffe9a8', 3, 90);
        bullets.splice(j, 1);
        if (b.hp <= 0) { killBoss(); return; }
      }
    }

    /* 撞机 */
    if (player.alive) {
      for (var w = 0; w < b.parts.length; w++) {
        var p2 = b.parts[w];
        var dx2 = player.x - (b.x + p2.dx), dy2 = player.y - (b.y + p2.dy), rr2 = p2.r + player.r * 0.7;
        if (dx2 * dx2 + dy2 * dy2 < rr2 * rr2) { hurtPlayer(); break; }
      }
    }
  }

  function killBoss() {
    var b = bossObj, i;
    for (i = 0; i < 5; i++) {
      var ang = Math.random() * PI2, dd = rand(0, 70);
      boom(b.x + Math.cos(ang) * dd, b.y + Math.sin(ang) * dd, i % 2 ? '#ffd166' : '#ff6b8a', 26, 340);
    }
    ring(b.x, b.y, '#ffd166', 20, 500);
    ring(b.x, b.y, '#ffffff', 40, 640);
    state.shake = 1.6;
    state.score += b.score;
    bossObj = null;
    sfx('boom');
    onWaveCleared();
  }

  /* ================= 绘制：背景 ================= */
  function drawSky() {
    var g = ctx.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, zone.bg[0]);
    g.addColorStop(0.55, '#05070f');
    g.addColorStop(1, zone.bg[1]);
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, W, H);

    for (var i = 0; i < nebs.length; i++) {
      var nb = nebs[i];
      var rg = ctx.createRadialGradient(nb.x, nb.y, 0, nb.x, nb.y, nb.r);
      var c = zone.neb;
      rg.addColorStop(0, 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + nb.a + ')');
      rg.addColorStop(1, 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',0)');
      ctx.fillStyle = rg;
      ctx.fillRect(nb.x - nb.r, nb.y - nb.r, nb.r * 2, nb.r * 2);
    }

    for (i = 0; i < stars.length; i++) {
      var s = stars[i];
      ctx.globalAlpha = 0.22 + s.z * 0.78;
      ctx.fillStyle = s.z > 0.74 ? zone.star : 'rgba(150,185,220,0.85)';
      ctx.fillRect(s.x, s.y, s.s, s.s * (1.6 + s.z * 2.6));
    }
    ctx.globalAlpha = 1;
  }

  /* ================= 绘制：我方战机 ================= */
  function drawFighter() {
    if (!player.alive) return;
    var p = player;
    ctx.save();
    ctx.translate(p.x, p.y);
    ctx.rotate(p.roll * 0.34);
    var blink = p.inv > 0 && (Math.floor(p.inv * 12) % 2 === 0);
    ctx.globalAlpha = blink ? 0.36 : 1;

    /* ---- 引擎尾焰 ---- */
    var fl = 16 + Math.random() * 14;
    var fg = ctx.createLinearGradient(0, 16, 0, 16 + fl);
    fg.addColorStop(0, 'rgba(180,240,255,0.95)');
    fg.addColorStop(0.35, 'rgba(90,190,255,0.62)');
    fg.addColorStop(1, 'rgba(60,120,255,0)');
    ctx.fillStyle = fg;
    var jet = [-6.5, 6.5];
    for (var jx = 0; jx < 2; jx++) {
      var cx = jet[jx];
      ctx.beginPath();
      ctx.moveTo(cx - 3.4, 15);
      ctx.lineTo(cx + 3.4, 15);
      ctx.lineTo(cx, 15 + fl);
      ctx.closePath();
      ctx.fill();
    }

    /* ---- 机翼下方挂载（导弹） ---- */
    ctx.fillStyle = '#c9d8e6';
    ctx.fillRect(-20.5, 4, 5.5, 12);
    ctx.fillRect(15, 4, 5.5, 12);
    ctx.fillStyle = '#ff6b5c';
    ctx.fillRect(-20.5, 4, 5.5, 3);
    ctx.fillRect(15, 4, 5.5, 3);

    /* ---- 主机身 + 机翼（一体化轮廓） ---- */
    var bg = ctx.createLinearGradient(-24, 0, 24, 0);
    bg.addColorStop(0, '#7f97ad');
    bg.addColorStop(0.18, '#dfeaf5');
    bg.addColorStop(0.5, '#ffffff');
    bg.addColorStop(0.82, '#dfeaf5');
    bg.addColorStop(1, '#7f97ad');
    ctx.shadowColor = '#66d4ff'; ctx.shadowBlur = 12;
    ctx.fillStyle = bg;
    ctx.beginPath();
    ctx.moveTo(0, -30);          /* 机头 */
    ctx.lineTo(2.6, -20);
    ctx.lineTo(3.6, -9);
    ctx.lineTo(7.5, -4);         /* 边条翼 */
    ctx.lineTo(23, 5.5);         /* 右翼前缘 */
    ctx.lineTo(24, 9.5);         /* 右翼尖 */
    ctx.lineTo(9.5, 7.5);        /* 右翼后缘 */
    ctx.lineTo(8, 13);
    ctx.lineTo(11, 17.5);        /* 右平尾 */
    ctx.lineTo(5, 15.5);
    ctx.lineTo(4.2, 20);
    ctx.lineTo(0, 18.6);
    ctx.lineTo(-4.2, 20);
    ctx.lineTo(-5, 15.5);
    ctx.lineTo(-11, 17.5);
    ctx.lineTo(-8, 13);
    ctx.lineTo(-9.5, 7.5);
    ctx.lineTo(-24, 9.5);
    ctx.lineTo(-23, 5.5);
    ctx.lineTo(-7.5, -4);
    ctx.lineTo(-3.6, -9);
    ctx.lineTo(-2.6, -20);
    ctx.closePath();
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.strokeStyle = 'rgba(120,190,235,0.9)';
    ctx.lineWidth = 1;
    ctx.stroke();

    /* ---- 机身中线分色 ---- */
    ctx.fillStyle = 'rgba(120,175,220,0.42)';
    ctx.beginPath();
    ctx.moveTo(0, -28.5); ctx.lineTo(2.4, -16); ctx.lineTo(2.2, 6);
    ctx.lineTo(4, 18); ctx.lineTo(-4, 18); ctx.lineTo(-2.2, 6);
    ctx.lineTo(-2.4, -16);
    ctx.closePath();
    ctx.fill();

    /* ---- 机翼细节线 ---- */
    ctx.strokeStyle = 'rgba(110,165,210,0.55)';
    ctx.lineWidth = 0.9;
    ctx.beginPath();
    ctx.moveTo(-8.5, 3.5); ctx.lineTo(-19.5, 8.2);
    ctx.moveTo(8.5, 3.5); ctx.lineTo(19.5, 8.2);
    ctx.stroke();

    /* ---- 座舱盖 ---- */
    var cg = ctx.createLinearGradient(0, -20, 0, -4);
    cg.addColorStop(0, '#eafaff');
    cg.addColorStop(0.5, '#4fb6ff');
    cg.addColorStop(1, 'rgba(20,80,160,0.95)');
    ctx.fillStyle = cg;
    ctx.beginPath();
    ctx.moveTo(0, -20.5);
    ctx.quadraticCurveTo(4.4, -16, 3.6, -6);
    ctx.quadraticCurveTo(0, -4.2, -3.6, -6);
    ctx.quadraticCurveTo(-4.4, -16, 0, -20.5);
    ctx.fill();
    ctx.strokeStyle = 'rgba(220,245,255,0.85)';
    ctx.lineWidth = 0.9;
    ctx.stroke();

    /* ---- 双垂尾（俯视表现为机身两侧小翼） ---- */
    ctx.fillStyle = '#b9cfe0';
    ctx.beginPath();
    ctx.moveTo(-5.4, 8); ctx.lineTo(-12.5, 15.5); ctx.lineTo(-9.6, 16.6); ctx.lineTo(-4.6, 12);
    ctx.closePath();
    ctx.fill();
    ctx.beginPath();
    ctx.moveTo(5.4, 8); ctx.lineTo(12.5, 15.5); ctx.lineTo(9.6, 16.6); ctx.lineTo(4.6, 12);
    ctx.closePath();
    ctx.fill();

    /* ---- 尾喷口 ---- */
    ctx.fillStyle = '#39424f';
    ctx.beginPath(); ctx.ellipse(-6.5, 17.4, 3.5, 2.3, 0, 0, PI2); ctx.fill();
    ctx.beginPath(); """,
    """ctx.ellipse(6.5, 17.4, 3.5, 2.3, 0, 0, PI2); ctx.fill();

    /* ---- 护盾环 ---- */
    if (p.inv > 0) {
      ctx.strokeStyle = 'rgba(120,230,255,' + (0.28 + 0.26 * Math.sin(state.time * 22)).toFixed(3) + ')';
      ctx.lineWidth = 2;
      ctx.beginPath(); ctx.arc(0, -2, 30, 0, PI2); ctx.stroke();
      ctx.strokeStyle = 'rgba(190,245,255,' + (0.14 + 0.14 * Math.sin(state.time * 22 + 1)).toFixed(3) + ')';
      ctx.lineWidth = 5;
      ctx.beginPath(); ctx.arc(0, -2, 30, 0, PI2); ctx.stroke();
    }
    ctx.restore();
    ctx.globalAlpha = 1;
  }

  /* ================= 绘制：外星飞船 ================= */
  function shipBody(e, drawFn) {
    var S = SHIPS[e.type];
    ctx.save();
    ctx.translate(e.x, e.y);
    drawFn(S, e.r, e.t, e);
    ctx.restore();
  }

  /* 侦查梭：纺锤 + 双翼 */
  function drawScout(S, r, t) {
    ctx.shadowColor = S.glow; ctx.shadowBlur = 10;
    var g = ctx.createLinearGradient(0, -r, 0, r);
    g.addColorStop(0, S.color); g.addColorStop(1, S.dark);
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.moveTo(0, r);
    ctx.lineTo(r * 0.72, 0);
    ctx.lineTo(r * 0.3, -r * 0.5);
    ctx.lineTo(r * 0.16, -r);
    ctx.lineTo(-r * 0.16, -r);
    ctx.lineTo(-r * 0.3, -r * 0.5);
    ctx.lineTo(-r * 0.72, 0);
    ctx.closePath();
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.strokeStyle = 'rgba(230,255,255,0.6)'; ctx.lineWidth = 1; ctx.stroke();
    ctx.fillStyle = 'rgba(255,255,255,0.95)';
    ctx.beginPath(); ctx.ellipse(0, -r * 0.15, r * 0.2, r * 0.42, 0, 0, PI2); ctx.fill();
    ctx.fillStyle = S.glow;
    ctx.beginPath(); ctx.arc(0, r * 0.55, r * 0.17, 0, PI2); ctx.fill();
  }

  /* 尖锥突袭机 */
  function drawDart(S, r, t) {
    ctx.rotate(Math.sin(t * 3) * 0.08);
    ctx.shadowColor = S.glow; ctx.shadowBlur = 10;
    var g = ctx.createLinearGradient(-r, -r, r, r);
    g.addColorStop(0, S.color); g.addColorStop(1, S.dark);
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.moveTo(0, r * 1.1);
    ctx.lineTo(r * 0.42, r * 0.1);
    ctx.lineTo(r * 1.0, -r * 0.55);
    ctx.lineTo(r * 0.3, -r * 0.65);
    ctx.lineTo(0, -r);
    ctx.lineTo(-r * 0.3, -r * 0.65);
    ctx.lineTo(-r * 1.0, -r * 0.55);
    ctx.lineTo(-r * 0.42, r * 0.1);
    ctx.closePath();
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.strokeStyle = 'rgba(230,255,220,0.6)'; ctx.lineWidth = 1; ctx.stroke();
    ctx.fillStyle = '#eaffdd';
    ctx.beginPath(); ctx.arc(0, -r * 0.1, r * 0.17, 0, PI2); ctx.fill();
  }

  /* 飞碟 */
  function drawSaucer(S, r, t) {
    /* 底盘 */
    ctx.shadowColor = S.glow; ctx.shadowBlur = 14;
    var g = ctx.createLinearGradient(0, -r * 0.45, 0, r * 0.55);
    g.addColorStop(0, S.color); g.addColorStop(1, S.dark);
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.ellipse(0, 0, r, r * 0.44, 0, 0, PI2);
    ctx.fill();
    ctx.shadowBlur = 0;
    /* 下缘 */
    ctx.fillStyle = 'rgba(0,0,0,0.30)';
    ctx.beginPath();
    ctx.ellipse(0, r * 0.12, r * 0.97, r * 0.3, 0, 0, Math.PI);
    ctx.fill();
    /* 穹顶 */
    var dg = ctx.createLinearGradient(0, -r * 0.95, 0, 0);
    dg.addColorStop(0, '#ffffff'); dg.addColorStop(1, S.glow);
    ctx.fillStyle = dg;
    ctx.beginPath();
    ctx.ellipse(0, -r * 0.14, r * 0.46, r * 0.56, 0, Math.PI, 0);
    ctx.fill();
    ctx.strokeStyle = 'rgba(255,255,255,0.7)'; ctx.lineWidth = 1; ctx.stroke();
    /* 灯 */
    for (var i = 0; i < 4; i++) {
      var a = t * 2 + i * PI2 / 4;
      var lx = Math.cos(a) * r * 0.74, ly = r * 0.1 + Math.sin(a) * r * 0.1;
      ctx.fillStyle = (Math.sin(t * 6 + i) > 0) ? '#fff6b0' : S.glow;
      ctx.shadowColor = '#fff6b0'; ctx.shadowBlur = 8;
      ctx.beginPath(); ctx.arc(lx, ly, r * 0.075, 0, PI2); ctx.fill();
      ctx.shadowBlur = 0;
    }
  }

  /* 眼球生物舰 */
  function drawEye(S, r, t) {
    /* 触须 */
    ctx.strokeStyle = S.dark; ctx.lineWidth = r * 0.11; ctx.lineCap = 'round';
    for (var i = 0; i < 5; i++) {
      var a = -PI2 * 0.72 + i * (PI2 * 0.44 / 4);
      var bx = Math.cos(a) * r * 0.7, by = Math.sin(a) * r * 0.7;
      ctx.beginPath();
      ctx.moveTo(bx * 0.6, by * 0.6);
      ctx.quadraticCurveTo(bx * 1.35, by * 1.35 + Math.sin(t * 3 + i) * 4, bx * 1.8, by * 1.55 + 8);
      ctx.stroke();
    }
    /* 主体 */
    ctx.shadowColor = S.glow; ctx.shadowBlur = 14;
    var g = ctx.createRadialGradient(-r * 0.3, -r * 0.35, r * 0.1, 0, 0, r);
    g.addColorStop(0, '#ffffff'); g.addColorStop(0.5, S.color); g.addColorStop(1, S.dark);
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(0, 0, r, 0, PI2); ctx.fill();
    ctx.shadowBlur = 0;
    /* 眼白 */
    ctx.fillStyle = 'rgba(255,255,255,0.94)';
    ctx.beginPath(); ctx.arc(0, 0, r * 0.56, 0, PI2); ctx.fill();
    /* 瞳孔（朝下看玩家） */
    var ax = Math.cos(t * 1.4) * r * 0.1, ay = r * 0.2;
    ctx.fillStyle = '#1b0d22';
    ctx.beginPath(); ctx.arc(ax, ay, r * 0.29, 0, PI2); ctx.fill();
    ctx.fillStyle = '#ff3b6b';
    ctx.beginPath(); ctx.arc(ax, ay, r * 0.14, 0, PI2); ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,0.9)';
    ctx.beginPath(); ctx.arc(ax - r * 0.1, ay - r * 0.1, r * 0.07, 0, PI2); ctx.fill();
  }

  /* 六边蜂巢舰 */
  function drawHex(S, r, t) {
    ctx.shadowColor = S.glow; ctx.shadowBlur = 12;
    var g = ctx.createLinearGradient(0, -r, 0, r);
    g.addColorStop(0, S.color); g.addColorStop(1, S.dark);
    ctx.fillStyle = g;
    ctx.beginPath();
    for (var i = 0; i < 6; i++) {
      var a = PI2 * i / 6 + Math.PI / 6;
      var px = Math.cos(a) * r, py = Math.sin(a) * r * 0.92;
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    }
    ctx.closePath();
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.strokeStyle = 'rgba(255,240,200,0.65)'; ctx.lineWidth = 1.2; ctx.stroke();
    /* 蜂巢纹 */
    ctx.strokeStyle = 'rgba(90,55,10,0.5)'; ctx.lineWidth = 0.9;
    for (i = 0; i < 6; i++) {
      var a2 = PI2 * i / 6 + Math.PI / 6;
      ctx.beginPath();
      ctx.moveTo(Math.cos(a2) * r * 0.42, Math.sin(a2) * r * 0.4);
      ctx.lineTo(Math.cos(a2) * r * 0.96, Math.sin(a2) * r * 0.88);
      ctx.stroke();
    }
    /* 核心 */
    var puls = 0.75 + Math.si""",
    """n(t * 4) * 0.25;
    ctx.fillStyle = S.glow;
    ctx.shadowColor = S.glow; ctx.shadowBlur = 16 * puls;
    ctx.beginPath(); ctx.arc(0, 0, r * 0.26 * puls + r * 0.12, 0, PI2); ctx.fill();
    ctx.shadowBlur = 0;
  }

  /* 孢子球 */
  function drawOrb(S, r, t) {
    /* 旋转环 */
    ctx.save();
    ctx.rotate(t * 1.2);
    ctx.strokeStyle = S.glow; ctx.lineWidth = 2;
    ctx.shadowColor = S.glow; ctx.shadowBlur = 10;
    ctx.beginPath(); ctx.ellipse(0, 0, r * 1.42, r * 0.38, 0, 0, PI2); ctx.stroke();
    ctx.restore();
    ctx.save();
    ctx.rotate(-t * 0.9 + 0.9);
    ctx.strokeStyle = 'rgba(255,255,255,0.55)'; ctx.lineWidth = 1.6;
    ctx.beginPath(); ctx.ellipse(0, 0, r * 1.2, r * 0.3, 0, 0, PI2); ctx.stroke();
    ctx.restore();
    /* 球体 */
    ctx.shadowColor = S.glow; ctx.shadowBlur = 14;
    var g = ctx.createRadialGradient(-r * 0.3, -r * 0.35, r * 0.1, 0, 0, r);
    g.addColorStop(0, '#ffffff'); g.addColorStop(0.45, S.color); g.addColorStop(1, S.dark);
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(0, 0, r * 0.78, 0, PI2); ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = 'rgba(255,255,255,0.5)';
    ctx.beginPath(); ctx.arc(-r * 0.22, -r * 0.26, r * 0.2, 0, PI2); ctx.fill();
  }

  /* 节肢蟹舰 */
  function drawCrab(S, r, t) {
    /* 腿 */
    ctx.strokeStyle = S.dark; ctx.lineWidth = r * 0.14; ctx.lineCap = 'round';
    for (var i = 0; i < 3; i++) {
      var yy = -r * 0.2 + i * r * 0.42;
      ctx.beginPath();
      ctx.moveTo(-r * 0.5, yy);
      ctx.quadraticCurveTo(-r * 1.05, yy - 6, -r * 1.25, yy + Math.sin(t * 4 + i) * 4 + 8);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(r * 0.5, yy);
      ctx.quadraticCurveTo(r * 1.05, yy - 6, r * 1.25, yy + Math.sin(t * 4 + i + 1) * 4 + 8);
      ctx.stroke();
    }
    /* 钳子 */
    ctx.fillStyle = S.dark;
    for (var s = -1; s <= 1; s += 2) {
      ctx.beginPath();
      ctx.ellipse(s * r * 0.86, -r * 0.55, r * 0.32, r * 0.22, s * 0.5, 0, PI2);
      ctx.fill();
      ctx.beginPath();
      ctx.moveTo(s * r * 0.72, -r * 0.72);
      ctx.lineTo(s * r * 1.32, -r * 0.95);
      ctx.lineTo(s * r * 1.1, -r * 0.55);
      ctx.closePath();
      ctx.fill();
    }
    /* 主体 */
    ctx.shadowColor = S.glow; ctx.shadowBlur = 13;
    var g = ctx.createLinearGradient(0, -r, 0, r);
    g.addColorStop(0, S.color); g.addColorStop(1, S.dark);
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.moveTo(0, r * 0.95);
    ctx.lineTo(r * 0.72, r * 0.35);
    ctx.lineTo(r * 0.6, -r * 0.7);
    ctx.lineTo(0, -r * 0.95);
    ctx.lineTo(-r * 0.6, -r * 0.7);
    ctx.lineTo(-r * 0.72, r * 0.35);
    ctx.closePath();
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.strokeStyle = 'rgba(255,220,190,0.6)'; ctx.lineWidth = 1.1; ctx.stroke();
    /* 复眼 */
    ctx.fillStyle = '#2a0d05';
    ctx.beginPath(); ctx.arc(-r * 0.26, -r * 0.18, r * 0.17, 0, PI2); ctx.fill();
    ctx.beginPath(); ctx.arc(r * 0.26, -r * 0.18, r * 0.17, 0, PI2); ctx.fill();
    ctx.fillStyle = S.glow;
    ctx.beginPath(); ctx.arc(-r * 0.26, -r * 0.18, r * 0.08, 0, PI2); ctx.fill();
    ctx.beginPath(); ctx.arc(r * 0.26, -r * 0.18, r * 0.08, 0, PI2); ctx.fill();
  }

  var DRAW = { scout: drawScout, dart: drawDart, saucer: drawSaucer, eye: drawEye, hex: drawHex, orb: drawOrb, crab: drawCrab };

  function drawEnemy(e) {
    var S = SHIPS[e.type];
    ctx.save();
    ctx.translate(e.x, e.y);
    DRAW[e.type](S, e.r, e.t, e);
    if (e.hit > 0) {
      var a = Math.min(0.8, e.hit * 8);
      var fg = ctx.createRadialGradient(0, 0, 0, 0, 0, e.r * 1.3);
      fg.addColorStop(0, 'rgba(255,255,255,' + a.toFixed(3) + ')');
      fg.addColorStop(0.55, 'rgba(255,255,255,' + (a * 0.45).toFixed(3) + ')');
      fg.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = fg;
      ctx.beginPath(); ctx.arc(0, 0, e.r * 1.3, 0, PI2); ctx.fill();
    }
    ctx.restore();

    /* 血条 */
    if (e.hp < e.maxHp) {
      var bw = e.r * 1.8, bh = 3;
      ctx.fillStyle = 'rgba(0,0,0,0.5)';
      ctx.fillRect(e.x - bw / 2, e.y - e.r - 12, bw, bh);
      ctx.fillStyle = S.glow;
      ctx.fillRect(e.x - bw / 2, e.y - e.r - 12, bw * (e.hp / e.maxHp), bh);
    }
  }

  /* ================= 绘制：Boss ================= */
  function drawBoss() {
    var b = bossObj;
    ctx.save();
    ctx.translate(b.x, b.y);
    var t = b.t;
    var glow = zone.neb;
    var main = 'rgb(' + Math.min(255, glow[0] + 90) + ',' + Math.min(255, glow[1] + 60) + ',' + Math.min(255, glow[2] + 90) + ')';
    var core = 'rgb(' + glow[0] + ',' + glow[1] + ',' + glow[2] + ')';

    /* 外侧旋转环 */
    ctx.save();
    ctx.rotate(t * 0.5);
    ctx.strokeStyle = 'rgba(255,255,255,0.18)';
    ctx.lineWidth = 2;
    for (var i = 0; i < 4; i++) {
      ctx.beginPath();
      ctx.arc(0, 0, 86, i * PI2 / 4, i * PI2 / 4 + 0.9);
      ctx.stroke();
    }
    ctx.restore();

    /* 侧舱 */
    for (var s = -1; s <= 1; s += 2) {
      ctx.save();
      ctx.translate(s * 56, 6);
      var sg = ctx.createLinearGradient(0, -26, 0, 26);
      sg.addColorStop(0, '#dfe9f5'); sg.addColorStop(1, '#3a4761');
      ctx.fillStyle = sg;
      ctx.shadowColor = core; ctx.shadowBlur = 12;
      ctx.beginPath();
      ctx.moveTo(0, 26); ctx.lineTo(20, 6); ctx.lineTo(14, -24);
      ctx.lineTo(-14, -24); ctx.lineTo(-20, 6);
      ctx.closePath();
      ctx.fill();
      ctx.shadowBlur = 0;
      ctx.strokeStyle = 'rgba(255,255,255,0.35)'; ctx.lineWidth = 1; ctx.stroke();
      ctx.fillStyle = core;
      ctx.beginPath(); ctx.arc(0, -4, 5 + Math.sin(t * 5 + s) * 1.6, 0, PI2); ctx.fill();
      ctx.restore();
    }

    /* 上部双炮 */
    for (var u = -1; u <= 1; u += 2) {
      ctx.save();
      ctx.translate(u * 26, -22);
      ctx.fillStyle = '#4d5a75';
      ctx.beginPath(); ctx.arc(0, 0, 13, 0, PI2); ctx.fill();
      ctx.fillStyle = core;
      ctx.beginPath(); ctx.arc(0, 0, 6, 0, PI2); ctx.fill();
      ctx.restore();
    }

    /* 中央主体 */
    var mg = ctx.createLinearGradient(0, -48, 0, 48);
    mg.addColorStop(0, '#f2f7ff'); mg.addColorStop(0.5, main); mg.addColorS""",
    """top(1, '#2a3550');
    ctx.shadowColor = core; ctx.shadowBlur = 22;
    ctx.fillStyle = mg;
    ctx.beginPath();
    ctx.moveTo(0, 50);
    ctx.lineTo(26, 34);
    ctx.lineTo(40, -6);
    ctx.lineTo(26, -42);
    ctx.lineTo(0, -52);
    ctx.lineTo(-26, -42);
    ctx.lineTo(-40, -6);
    ctx.lineTo(-26, 34);
    ctx.closePath();
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.strokeStyle = 'rgba(255,255,255,0.5)'; ctx.lineWidth = 1.4; ctx.stroke();

    /* 中央核心（脉动） */
    var puls = 0.8 + Math.sin(t * 3.4) * 0.2;
    var rg = ctx.createRadialGradient(0, 0, 2, 0, 0, 30 * puls);
    rg.addColorStop(0, '#ffffff');
    rg.addColorStop(0.35, core);
    rg.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = rg;
    ctx.beginPath(); ctx.arc(0, 0, 32 * puls, 0, PI2); ctx.fill();

    /* 受击白闪 */
    if (b.hit > 0) {
      var a2 = Math.min(0.62, b.hit * 7);
      var fg3 = ctx.createRadialGradient(0, 0, 0, 0, 0, 62);
      fg3.addColorStop(0, 'rgba(255,255,255,' + a2.toFixed(3) + ')');
      fg3.addColorStop(0.5, 'rgba(255,255,255,' + (a2 * 0.4).toFixed(3) + ')');
      fg3.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = fg3;
      ctx.beginPath(); ctx.arc(0, 0, 62, 0, PI2); ctx.fill();
    }
    ctx.restore();

    /* Boss 血条（屏幕顶部，避开 HUD） */
    var bw = W - 72, bh = 7, bx = 36, by = 88;
    ctx.fillStyle = 'rgba(0,0,0,0.45)';
    ctx.fillRect(bx, by, bw, bh);
    var hg = ctx.createLinearGradient(bx, 0, bx + bw, 0);
    hg.addColorStop(0, '#ff2d55'); hg.addColorStop(0.6, '#ff7a3a'); hg.addColorStop(1, '#ffd166');
    ctx.fillStyle = hg;
    ctx.fillRect(bx, by, bw * Math.max(0, b.hp / b.maxHp), bh);
    ctx.strokeStyle = 'rgba(255,255,255,0.35)'; ctx.lineWidth = 1;
    ctx.strokeRect(bx + 0.5, by + 0.5, bw - 1, bh - 1);
  }

  /* ================= 绘制：子弹 / 道具 / 粒子 ================= */
  function drawBullets() {
    for (var i = 0; i < bullets.length; i++) {
      var b = bullets[i];
      ctx.shadowColor = '#ffe27a'; ctx.shadowBlur = 8;
      var g = ctx.createLinearGradient(0, b.y - 12, 0, b.y + 12);
      g.addColorStop(0, '#fffdf0'); g.addColorStop(0.45, '#ffe27a');
      g.addColorStop(1, 'rgba(255,170,50,0)');
      ctx.fillStyle = g;
      ctx.fillRect(b.x - b.r * 0.5, b.y - b.r * 2.4, b.r, b.r * 4.8);
    }
    ctx.shadowBlur = 0;
  }

  function drawEBullets() {
    for (var i = 0; i < ebullets.length; i++) {
      var b = ebullets[i];
      ctx.shadowColor = '#ff5df0'; ctx.shadowBlur = 12;
      ctx.fillStyle = '#ffa8f5';
      ctx.beginPath(); ctx.arc(b.x, b.y, b.r, 0, PI2); ctx.fill();
      ctx.shadowBlur = 0;
      ctx.fillStyle = 'rgba(255,255,255,0.92)';
      ctx.beginPath(); ctx.arc(b.x, b.y, b.r * 0.4, 0, PI2); ctx.fill();
    }
  }

  function drawItems() {
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var col = it.type === 'power' ? '#ffd166' : '#ff5d8f';
      ctx.save();
      ctx.translate(it.x, it.y);
      var pulse = 1 + Math.sin(it.t * 6) * 0.09;
      ctx.scale(pulse, pulse);
      ctx.shadowColor = col; ctx.shadowBlur = 16;
      ctx.fillStyle = col;
      ctx.beginPath();
      ctx.moveTo(0, -12); ctx.lineTo(12, 0); ctx.lineTo(0, 12); ctx.lineTo(-12, 0);
      ctx.closePath(); ctx.fill();
      ctx.shadowBlur = 0;
      ctx.restore();

      ctx.save();
      ctx.translate(it.x, it.y);
      ctx.fillStyle = it.type === 'power' ? '#3a2400' : '#3a0016';
      ctx.font = 'bold 12px -apple-system,sans-serif';
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText(it.type === 'power' ? 'P' : '♥', 0, 1);
      ctx.restore();
    }
  }

  function drawParticles() {
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      var a = p.life / p.max;
      if (p.ring) {
        ctx.globalAlpha = a * 0.8;
        ctx.strokeStyle = p.color;
        ctx.lineWidth = 2.5 * a + 0.5;
        ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, PI2); ctx.stroke();
        continue;
      }
      ctx.globalAlpha = a;
      ctx.fillStyle = p.color;
      ctx.beginPath(); ctx.arc(p.x, p.y, p.r * (0.4 + a * 0.6), 0, PI2); ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  /* ================= 渲染 ================= */
  function drawCinematic() {
    var t = state.cineT;
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    var cx = W / 2, cy = H * 0.42;
    var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(W, H) * 0.85);
    g.addColorStop(0, 'rgba(18,48,96,1)');
    g.addColorStop(0.5, 'rgba(6,10,22,1)');
    g.addColorStop(1, 'rgba(2,3,8,1)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, W, H);

    if (t < 0.55) {
      ctx.fillStyle = 'rgba(222,242,255,' + ((1 - t / 0.55) * 0.92).toFixed(3) + ')';
      ctx.fillRect(0, 0, W, H);
    }
    if (t < 0.9) {
      ctx.strokeStyle = 'rgba(180,225,255,' + ((1 - t / 0.9) * 0.9).toFixed(3) + ')';
      ctx.lineWidth = 3;
      ctx.beginPath(); ctx.arc(cx, cy, t * 900, 0, PI2); ctx.stroke();
    }

    var fade = Math.min(1, Math.max(0, (t - 0.3) / 0.5));
    ctx.lineCap = 'round';
    for (var i = 0; i < cineParts.length; i++) {
      var p = cineParts[i];
      var ca = Math.cos(p.a), sa = Math.sin(p.a);
      var len = p.w * (0.6 + p.r * 0.17);
      var al = fade * Math.min(1, p.r / (cineMax * 0.45)) * 0.9;
      ctx.strokeStyle = 'rgba(190,232,255,' + al.toFixed(3) + ')';
      ctx.lineWidth = p.w;
      ctx.beginPath();
      ctx.moveTo(cx + ca * p.r, cy + sa * p.r);
      ctx.lineTo(cx + ca * (p.r + len), cy + sa * (p.r + len));
      ctx.stroke();
    }

    var cg = ctx.createRadialGradient(cx, cy, 0, cx, cy, 92);
    cg.addColorStop(0, 'rgba(255,255,255,0.95)');
    cg.addColorStop(0.4, 'rgba(130,200,255,0.5)');
    cg.addColorStop(1, 'rgba(80,150,255,0)');
    ctx.fillStyle = cg;
    ctx.beginPath(); ctx.arc(cx, cy, 92, 0, PI2); ctx.fill();

    if (t > 4.3) {
      ctx.fillStyle = 'rgba(3,5,12,' + Math.min(1, (t - 4.3) / 0.7).toFixed(3) + ')';
      ctx.fillRect(0, 0, W, H);
    }
  }

  function render() {
    if (state.mode === 'cinematic') { drawCinem""",
    """atic(); return; }
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    drawSky();

    ctx.save();
    if (state.shake > 0) {
      var s = state.shake * 9;
      ctx.translate((Math.random() - 0.5) * s, (Math.random() - 0.5) * s);
    }

    drawItems();
    for (var i = 0; i < enemies.length; i++) drawEnemy(enemies[i]);
    if (bossObj) drawBoss();
    drawBullets();
    drawFighter();
    drawEBullets();
    drawParticles();

    ctx.restore();

    /* 母舰来袭预警：屏幕边缘红光脉冲 */
    if (state.bossWarn > 0) {
      var ba = (0.20 + 0.30 * Math.abs(Math.sin(state.time * 7))) * Math.min(1, state.bossWarn / 0.7);
      var lg = ctx.createLinearGradient(0, 0, 0, H * 0.36);
      lg.addColorStop(0, 'rgba(255,40,70,' + ba.toFixed(3) + ')');
      lg.addColorStop(1, 'rgba(255,40,70,0)');
      ctx.fillStyle = lg;
      ctx.fillRect(0, 0, W, H * 0.36);
      var lg2 = ctx.createLinearGradient(0, H, 0, H * 0.72);
      lg2.addColorStop(0, 'rgba(255,40,70,' + (ba * 0.45).toFixed(3) + ')');
      lg2.addColorStop(1, 'rgba(255,40,70,0)');
      ctx.fillStyle = lg2;
      ctx.fillRect(0, H * 0.72, W, H * 0.28);
    }
  }

  /* ================= HUD ================= */
  var scoreVal = $('scoreVal'), stageSub = $('stageSub'), heartsEl = $('hearts'),
      powerTrack = $('powerTrack'), progFill = $('progFill'), bestSub = $('bestSub');

  function updateHUD() {
    scoreVal.textContent = state.score;
    stageSub.textContent = '第 ' + state.chapter + ' 关 · ' + state.wave + ' / ' + WAVES + ' 波';
    bestSub.textContent = '最高 ' + Math.max(state.best, state.score) + '分';
    var h = '';
    for (var i = 0; i < player.hp; i++) h += '<span class="hp">♥</span>';
    heartsEl.innerHTML = h;
    var dots = powerTrack.children;
    for (var d = 0; d < dots.length; d++) dots[d].className = d < player.power ? 'on' : '';
    var prog;
    if (cfg.boss) prog = bossObj ? clamp(bossObj.hp / bossObj.maxHp, 0, 1) : 0;
    else prog = clamp(state.kills / cfg.quota, 0, 1);
    progFill.style.width = (prog * 100).toFixed(1) + '%';
    progFill.className = 'prog-fill' + (cfg.boss ? ' boss' : '');
  }

  /* ================= 事件绑定 ================= */
  function backToMenu() {
    state.mode = 'menu';
    state.paused = false;
    hideAllLayers();
    $('banner').classList.remove('show');
    $('panelOver').classList.add('hide');
    $('panelMenu').classList.remove('hide');
    $('overlay').classList.remove('hide');
    $('hud').classList.add('hide');
    $('menuBest').textContent = '最高 ' + state.best + ' 分 · 第 ' + state.bestStage + ' 关';
  }

  $('btnStart').addEventListener('click', startGame);
  $('btnRetry').addEventListener('click', startGame);
  $('btnEndRetry').addEventListener('click', startGame);
  $('btnMenu').addEventListener('click', backToMenu);
  $('btnQuit').addEventListener('click', backToMenu);
  $('btnRestart').addEventListener('click', startGame);
  $('btnResume').addEventListener('click', resumeGame);
  $('storyBtn').addEventListener('click', closeStory);
  $('pauseBtn').addEventListener('click', pauseGame);
  $('muteBtn').addEventListener('click', function () {
    muted = !muted;
    this.textContent = muted ? '🔇' : '🔊';
    if (!muted) initAudio();
  });
  $('menuBest').textContent = '最高 ' + state.best + ' 分 · 第 ' + state.bestStage + ' 关';

  /* ================= 主循环 ================= */
  var last = 0;
  function loop(ts) {
    requestAnimationFrame(loop);
    if (!last) last = ts;
    var dt = (ts - last) / 1000;
    last = ts;
    if (dt > 0.05) dt = 0.05;
    if (dt < 0) dt = 0;

    if (state.mode === 'cinematic') {
      updateCinematic(dt);
    } else if ((state.mode === 'playing' || state.mode === 'clear') && !state.paused) {
      update(dt);
    } else if (state.mode !== 'cinematic' && !state.paused) {
      /* 菜单/剧情/结算：背景继续流动 */
      var b = 1 + Math.min(1.1, stageNo() * 0.012);
      for (var i = 0; i < stars.length; i++) {
        stars[i].y += stars[i].v * dt * 0.45 * b;
        if (stars[i].y > H + 2) { stars[i].y = -2; stars[i].x = Math.random() * W; }
      }
      for (i = 0; i < nebs.length; i++) {
        nebs[i].y += nebs[i].sy * dt * 0.45;
        if (nebs[i].y + nebs[i].r < -20) { nebs[i].y = H + nebs[i].r * 0.6; nebs[i].x = Math.random() * W; }
      }
      for (i = parts.length - 1; i >= 0; i--) {
        var p = parts[i];
        p.life -= dt;
        if (p.life <= 0) { parts.splice(i, 1); continue; }
        if (p.ring) { p.r += p.vr * dt; continue; }
        p.x += p.vx * dt; p.y += p.vy * dt;
      }
    }
    render();
  }

  /* ================= 启动 ================= */
  resize();
  if (window.ResizeObserver) {
    new ResizeObserver(function () { resize(); }).observe(stage);
  }
  window.addEventListener('resize', resize);
  window.addEventListener('orientationchange', function () { setTimeout(resize, 260); });
  document.addEventListener('visibilitychange', function () { last = 0; });

  updateHUD();
  requestAnimationFrame(loop);
})();
</script>
</body>
</html>
""",
)

/** 游戏页面 HTML（分片拼接，见上方说明）。 */
internal val PlaneShooterHtml: String = PlaneShooterHtmlChunks.joinToString("")
