const $ = id => document.getElementById(id);
const token = new URLSearchParams(location.search).get('session');
const emptyRules = {schema_version:1,enabled:true,default_pose:'epic_fight',overrides:[],extras:[]};
let model=null, replay=null, actions=[], current=0, playing=false, lastTime=0, busyCount=0;
let yaw=.32, elevation=.12, zoom=1, pan=[0,0], framing=null, drag=null, textureRequest=0;
let loadedModel='', sampleGeneration=0, debounceTimer;
let externalAnimations={}, assetImportGeneration=0;
$('rules').value=JSON.stringify(emptyRules,null,2);

function error(message=''){$('error').hidden=!message;$('error').textContent=message;}
function busy(on,text='正在重建动作…'){
  busyCount=Math.max(0,busyCount+(on?1:-1));$('busy').hidden=!busyCount;$('busyText').textContent=text;
  for(const id of ['rebuild','scan','browse'])$(id).disabled=!!busyCount;
}
async function api(command, body={}){
  const response=await fetch('/api/'+command,{method:'POST',headers:{'Content-Type':'application/json','X-Preview-Token':token},body:JSON.stringify(body)});
  const result=await response.json();if(!response.ok)throw Error(result.error||'操作失败');return result;
}
function option(select,value,label=value){const element=document.createElement('option');element.value=value;element.textContent=label;select.append(element);}
function setPlaying(value){playing=!!value&&!!replay;$('play').textContent=playing?'暂停':'播放';$('play').setAttribute('aria-label',playing?'暂停':'播放');lastTime=0;}
const friendly={'living/idle':'站立','living/walk':'行走','living/run':'奔跑','living/swim':'游泳','living/float':'水中停留','living/fly':'飞行','living/jump':'跳跃','living/hold_greatsword':'巨剑待机','combat/greatsword_auto1':'巨剑 · 第一段','combat/greatsword_auto2':'巨剑 · 第二段','combat/greatsword_auto3':'巨剑 · 第三段'};
function actionLabel(id){const short=id.replace('epicfight:biped/','');return friendly[short]?friendly[short]+'  /  '+short:short;}
function listActions(prefer){
  const previous=prefer||$('epic').value,term=$('search').value.toLowerCase();$('epic').replaceChildren();
  for(const id of actions)if((id+' '+actionLabel(id)).toLowerCase().includes(term))option($('epic'),id,actionLabel(id));
  if([...$('epic').options].some(o=>o.value===previous))$('epic').value=previous;
  else if($('epic').options.length)$('epic').selectedIndex=0;
}
function inferredMovement(){
  if($('movement').value!=='auto')return $('movement').value;
  const name=$('epic').value.split('/').at(-1);
  return ({idle:'idle',walk:'walk',run:'run',swim:'swim',float:'water_idle',jump:'jump',fly:'creative_flight',creative_fly:'creative_flight',elytra_fly:'elytra_flight',sneak:'sneak_move',sneaking:'sneak_idle',crawl:'crawl_move',climb:'ladder_up'})[name]||'idle';
}
async function scan(){
  busy(true,'正在读取模型库…');error();
  try{
    const data=await api('catalogue',{root:$('root').value});const previous=$('model').value;
    $('model').replaceChildren();for(const id of Object.keys(data.models).sort())option($('model'),id);
    actions=data.epic;listActions('epicfight:biped/living/idle');
    if(!Object.keys(data.models).length)throw Error('没有找到模型。请选择包含 builtin、custom 或 auth 的 yes_steve_model 目录。');
    if([...$('model').options].some(o=>o.value===previous))$('model').value=previous;
    await loadModel();
  }catch(e){error(e.message);}finally{busy(false);}
}
async function loadModel(){
  busy(true,'正在转换所选模型…');error();setPlaying(false);sampleGeneration++;
  const selected=$('model').value;
  try{
    const data=await api('inspect',{model:selected});
    model=data;loadedModel=selected;replay=null;framing=null;
    $('modelName').textContent=selected;$('metrics').textContent=`${data.sourceBones} 个骨骼 · ${data.mappedBones} 个已映射 · ${data.faces.toLocaleString()} 个面`;
    const warning=[];if(data.sourceBones>data.mappedBones)warning.push(`此模型有 ${data.sourceBones} 个骨骼，当前转换器只独立映射了 ${data.mappedBones} 个；超出的骨骼不能完整重建。`);
    if(!Object.keys(data.textures).length)warning.push('没有可读取的 PNG/JPEG 贴图，暂用浅色材质显示。');
    $('warning').textContent=warning.join('\n');$('warning').hidden=!warning.length;
    $('texture').replaceChildren();for(const name of Object.keys(data.textures))option($('texture'),name);
    if(data.textures[data.defaultTexture])$('texture').value=data.defaultTexture;
    $('clip').replaceChildren();for(const name of data.clips)option($('clip'),name);
    $('controller').replaceChildren();for(const name of data.controllers||[])option($('controller'),name);
    if(data.clips.includes('idle'))$('clip').value='idle';
    renderer.geometry(data);await renderer.texture(data.textures[$('texture').value]);
    await rebuild();
  }catch(e){error('模型未切换：'+e.message);if(loadedModel)$('model').value=loadedModel;}
  finally{busy(false);}
}
function decodeReplay(data){
  if(data.schema!==1||!Number.isInteger(data.poses)||data.poses<20||data.poses>4116||data.frames<1||data.frames>161)throw Error('预览帧格式不支持');
  const raw=atob(data.matrices),expected=data.frames*2*data.poses*16*4;
  if(raw.length!==expected)throw Error('预览帧长度不完整');
  const bytes=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);
  return {...data,values:new Float32Array(bytes.buffer)};
}
async function rebuild(){
  return rebuildAnimations(externalAnimations);
}
async function rebuildAnimations(candidateAnimations){
  if(!model||!$('epic').value){error('请先选择可预览的模型和动作');return false;}
  const generation=++sampleGeneration;busy(true,'正在计算两个版本的姿态…');error();setPlaying(false);
  try{
    const rules=JSON.parse($('rules').value);const movement=inferredMovement();
    const facts={movement,pitch:+$('pitch').value,yaw:+$('yaw').value,attacking:$('attacking').checked,hurt:$('hurt').checked,battle_mode:true,
      on_ground:!['jump','swim','water_idle','creative_flight','elytra_flight'].includes(movement),in_water:['swim','water_idle'].includes(movement),
      sneaking:movement.startsWith('sneak'),sprinting:movement==='run',using_item:$('usingItem').checked,item:$('item').value.trim()||'minecraft:air'};
    facts.battle_mode=$('battleMode').checked;
    for(const [id,key] of [['yawSpeed','yaw_speed'],['velocityX','velocity_x'],['velocityY','velocity_y'],['velocityZ','velocity_z']]){
      if($(id).value!==''){
        const value=Number($(id).value);if(!Number.isFinite(value))throw Error('运动输入必须为有限数值');facts[key]=value;
      }
    }
    const request={model:loadedModel,epic:$('epic').value,mode:$('mode').value,clip:$('clip').value,controller:$('controller').value,rules,facts,externalAnimations:candidateAnimations};
    if($('seconds').value!=='auto')request.seconds=+$('seconds').value;
    const result=await api('sample',request);if(generation!==sampleGeneration)return false;
    replay=decodeReplay(result);current=0;$('timeline').max=replay.frames-1;$('timeline').value=0;
    document.querySelector('.panel-labels > span').textContent='EF 基础（保留模型外观）';
    $('rightLabel').replaceChildren(document.createTextNode(({rules:'当前动作规则',clip:'指定模型片段',controller:'指定模型控制器',ef:'EF 基础姿态'})[$('mode').value]));
    const note=document.createElement('span');note.className='sub-label';note.textContent=$('mode').value==='clip'?$('clip').value:$('mode').value==='controller'?$('controller').value:'相同动作与输入';$('rightLabel').append(note);
    $('unknownDetails').hidden=!replay.unhandled.length;$('unknown').textContent=replay.unhandled.join('、');
    framing=renderer.bounds();zoom=1;pan=[0,0];draw();
    // Publish resources only after the worker accepted and the replay decoded successfully.
    externalAnimations=candidateAnimations;
    return true;
  }catch(e){if(generation===sampleGeneration)error('本次重建未生效，保留已载入的额外动作：'+e.message);return false;}
  finally{busy(false);}
}

const canvas=$('scene'),gl=canvas.getContext('webgl2',{antialias:true,alpha:false,preserveDrawingBuffer:false});
if(!gl){error('当前窗口没有 WebGL2。请使用支持 WebGL2 的浏览器打开本机预览地址。');throw Error('WebGL2 unavailable');}
function shader(type,source){const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s;}
function program(vertex,fragment){const p=gl.createProgram();gl.attachShader(p,shader(gl.VERTEX_SHADER,vertex));gl.attachShader(p,shader(gl.FRAGMENT_SHADER,fragment));gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(p));return p;}
const meshProgram=program(`#version 300 es
in vec3 position;in vec3 normal;in vec2 texcoord;in float owner;
uniform mat4 vp;uniform sampler2D bones;out vec2 uv;out float light;out float visible;
void main(){int j=int(owner);int x=(j%16)*4;int y=j/16;mat4 skin=mat4(texelFetch(bones,ivec2(x,y),0),texelFetch(bones,ivec2(x+1,y),0),texelFetch(bones,ivec2(x+2,y),0),texelFetch(bones,ivec2(x+3,y),0));
uv=texcoord;vec3 n=mat3(skin)*normal;visible=length(n)>.000001?1.:0.;light=.78+.22*abs(dot(normalize(n+vec3(.0000001)),normalize(vec3(.3,.8,-.5))));gl_Position=vp*skin*vec4(position,1.);}`,
`#version 300 es
precision highp float;in vec2 uv;in float light;in float visible;uniform sampler2D atlas;out vec4 color;
void main(){if(visible<.5)discard;vec4 tex=texture(atlas,uv);if(tex.a<.1)discard;color=vec4(tex.rgb*light,1.);}`);
const lineProgram=program(`#version 300 es
in vec3 point;uniform mat4 vp;void main(){gl_Position=vp*vec4(point,1.);}`,
`#version 300 es
precision highp float;uniform vec4 tint;out vec4 color;void main(){color=tint;}`);
const meshVao=gl.createVertexArray(),lineVao=gl.createVertexArray(),lineBuffer=gl.createBuffer();
gl.bindVertexArray(lineVao);gl.bindBuffer(gl.ARRAY_BUFFER,lineBuffer);const lineLoc=gl.getAttribLocation(lineProgram,'point');gl.enableVertexAttribArray(lineLoc);gl.vertexAttribPointer(lineLoc,3,gl.FLOAT,false,0,0);
const attributes=[],indexBuffer=gl.createBuffer(),boneTexture=gl.createTexture(),atlas=gl.createTexture();let indexCount=0;
let palette=null,paletteRows=0;
function setupTexture(texture){gl.bindTexture(gl.TEXTURE_2D,texture);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);}
gl.activeTexture(gl.TEXTURE0);setupTexture(atlas);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([216,223,211,255]));
gl.activeTexture(gl.TEXTURE1);setupTexture(boneTexture);gl.enable(gl.DEPTH_TEST);gl.disable(gl.CULL_FACE);
const sub=(a,b)=>a.map((x,i)=>x-b[i]),dot=(a,b)=>a.reduce((sum,x,i)=>sum+x*b[i],0),cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],norm=a=>{const n=Math.hypot(...a)||1;return a.map(x=>x/n);};
function transform(data,offset,x,y,z){return[data[offset]*x+data[offset+4]*y+data[offset+8]*z+data[offset+12],data[offset+1]*x+data[offset+5]*y+data[offset+9]*z+data[offset+13],data[offset+2]*x+data[offset+6]*y+data[offset+10]*z+data[offset+14]];}
function skinAt(frame,side){const size=replay.poses*16,start=(frame*2+side)*size;return replay.values.subarray(start,start+size);}
const renderer={
  geometry(data){
    gl.bindVertexArray(meshVao);for(const buffer of attributes)gl.deleteBuffer(buffer);attributes.length=0;
    for(const [name,values,size] of [['position',data.positions,3],['normal',data.normals,3],['texcoord',data.uvs,2],['owner',data.owners,1]]){
      const buffer=gl.createBuffer();attributes.push(buffer);gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(values),gl.STATIC_DRAW);
      const location=gl.getAttribLocation(meshProgram,name);gl.enableVertexAttribArray(location);gl.vertexAttribPointer(location,size,gl.FLOAT,false,0,0);
    }
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,indexBuffer);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,new Uint32Array(data.triangles),gl.STATIC_DRAW);indexCount=data.triangles.length;
    paletteRows=Math.ceil(data.poseCount/16);palette=new Float32Array(64*paletteRows*4);
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,boneTexture);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA32F,64,paletteRows,0,gl.RGBA,gl.FLOAT,null);
  },
  async texture(source){
    const generation=++textureRequest;let image=null;
    if(source){image=new Image();image.src=source;await image.decode();}
    if(generation!==textureRequest)return;gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,atlas);
    if(image)gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,image);
    else gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([216,223,211,255]));draw();
  },
  bounds(){
    const lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity],step=Math.max(1,Math.floor(model.owners.length/2500));
    for(let frame=0;frame<replay.frames;frame+=Math.max(1,Math.floor(replay.frames/20)))for(let side=0;side<2;side++){
      const skin=skinAt(frame,side);for(let vertex=0;vertex<model.owners.length;vertex+=frame===0?1:step){
        const at=model.owners[vertex]*16,offset=vertex*3;
        if(Math.abs(skin[at])+Math.abs(skin[at+5])+Math.abs(skin[at+10])<1e-9)continue;
        const point=transform(skin,at,...model.positions.slice(offset,offset+3));
        if(point.some(v=>!Number.isFinite(v)||Math.abs(v)>10000))continue;
        for(let axis=0;axis<3;axis++){lo[axis]=Math.min(lo[axis],point[axis]);hi[axis]=Math.max(hi[axis],point[axis]);}
      }
    }
    if(!lo.every(Number.isFinite))return{center:[0,1,0],radius:1.5,floor:0};
    return{center:lo.map((v,i)=>(v+hi[i])/2),radius:Math.max(.1,Math.hypot(...sub(hi,lo))/2)*1.08,floor:lo[1]};
  }
};
function lines(points,vp,tint){gl.useProgram(lineProgram);gl.bindVertexArray(lineVao);gl.bindBuffer(gl.ARRAY_BUFFER,lineBuffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(points),gl.DYNAMIC_DRAW);gl.uniformMatrix4fv(gl.getUniformLocation(lineProgram,'vp'),false,vp);gl.uniform4fv(gl.getUniformLocation(lineProgram,'tint'),tint);gl.drawArrays(gl.LINES,0,points.length/3);}
function draw(){
  if(!model||!replay||!framing)return;
  const ratio=Math.min(devicePixelRatio||1,2),width=Math.max(2,Math.floor(canvas.clientWidth*ratio)),height=Math.max(2,Math.floor(canvas.clientHeight*ratio));
  if(canvas.width!==width||canvas.height!==height){canvas.width=width;canvas.height=height;}
  gl.viewport(0,0,width,height);gl.clearColor(.9137,.9294,.8941,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
  const half=Math.floor(width/2),direction=norm([Math.sin(yaw)*Math.cos(elevation),Math.sin(elevation),-Math.cos(yaw)*Math.cos(elevation)]),right=norm(cross(direction,[0,1,0])),up=cross(right,direction);
  const hh=framing.radius/zoom,hw=hh*half/height,center=framing.center.map((v,i)=>v+right[i]*pan[0]+up[i]*pan[1]);
  const depth=Math.max(10,framing.radius*10);
  const vp=new Float32Array([right[0]/hw,up[0]/hh,-direction[0]/depth,0,right[1]/hw,up[1]/hh,-direction[1]/depth,0,right[2]/hw,up[2]/hh,-direction[2]/depth,0,-dot(right,center)/hw,-dot(up,center)/hh,dot(direction,center)/depth,1]);
  for(let side=0;side<2;side++){
    gl.viewport(side*half,0,side?width-half:half,height);const grid=[],extent=Math.max(2,Math.ceil(framing.radius*2));
    for(let i=-extent;i<=extent;i++){grid.push(i,framing.floor,-extent,i,framing.floor,extent,-extent,framing.floor,i,extent,framing.floor,i);}
    lines(grid,vp,[.80,.84,.77,1]);
    gl.useProgram(meshProgram);gl.bindVertexArray(meshVao);gl.uniformMatrix4fv(gl.getUniformLocation(meshProgram,'vp'),false,vp);
    gl.uniform1i(gl.getUniformLocation(meshProgram,'atlas'),0);gl.uniform1i(gl.getUniformLocation(meshProgram,'bones'),1);
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,boneTexture);const skin=skinAt(current,side);
    palette.set(skin);gl.texSubImage2D(gl.TEXTURE_2D,0,0,0,64,paletteRows,gl.RGBA,gl.FLOAT,palette);gl.drawElements(gl.TRIANGLES,indexCount,gl.UNSIGNED_INT,0);
    if($('wire').checked){const points=[];for(const bone of model.bones){if(bone.parent<0)continue;const parent=model.bones[bone.parent];points.push(...transform(skin,bone.pose*16,...bone.pivot),...transform(skin,parent.pose*16,...parent.pivot));}
      gl.disable(gl.DEPTH_TEST);lines(points,vp,[.78,.36,.19,1]);gl.enable(gl.DEPTH_TEST);}
  }
  $('timeline').value=current;$('time').textContent=`${Math.min(current/replay.fps,replay.seconds).toFixed(2)} / ${replay.seconds.toFixed(2)} 秒`;
  $('diagnostic').textContent=replay.diagnostics[current]||'';
}

$('scan').onclick=scan;$('browse').onclick=async()=>{try{const result=await api('browse');if(!result.cancelled){$('root').value=result.path;await scan();}}catch(e){error(e.message);}};
$('model').onchange=loadModel;$('texture').onchange=()=>renderer.texture(model.textures[$('texture').value]).catch(e=>error(e.message));
$('search').oninput=()=>listActions();$('epic').onchange=()=>{if($('epic').value.includes('/combat/'))$('attacking').checked=true;else $('attacking').checked=false;rebuild();};
$('mode').onchange=()=>{$('clipRow').hidden=$('mode').value!=='clip';$('controllerRow').hidden=$('mode').value!=='controller';rebuild();};
for(const id of ['movement','clip','controller','attacking','hurt','seconds','item','usingItem','battleMode','yawSpeed','velocityX','velocityY','velocityZ'])$(id).onchange=rebuild;
$('loadReport').onclick=()=>$('reportFile').click();
$('reportFile').onchange=async()=>{
  const file=$('reportFile').files[0];if(!file)return;
  const generation=++sampleGeneration;busy(true,'正在载入游戏记录帧…');setPlaying(false);error();
  try{
    if(!model)throw Error('请先载入模型库和对应模型');
    if(file.size>16*1024*1024)throw Error('问题记录超过 16 MiB');
    const report=JSON.parse(await file.text());
    const result=await api('recorded',{model:loadedModel,report});if(generation!==sampleGeneration)return;
    replay=decodeReplay(result);current=0;$('timeline').max=0;$('timeline').value=0;
    document.querySelector('.panel-labels > span').textContent='记录的 EF 输入（未叠加外观层）';
    $('rightLabel').textContent='游戏记录的最终模型姿态';
    $('unknownDetails').hidden=!replay.unhandled.length;$('unknown').textContent=replay.unhandled.join('、');
    framing=renderer.bounds();zoom=1;pan=[0,0];draw();
  }catch(e){if(generation===sampleGeneration)error('记录未载入：'+e.message);}
  finally{busy(false);$('reportFile').value='';}
};
for(const id of ['pitch','yaw'])$(id).oninput=()=>{$(id+'Value').textContent=$(id).value+'°';clearTimeout(debounceTimer);debounceTimer=setTimeout(rebuild,250);};
$('rebuild').onclick=rebuild;$('wire').onchange=draw;
$('play').onclick=()=>setPlaying(!playing);$('timeline').oninput=()=>{setPlaying(false);current=+$('timeline').value;draw();};
function step(direction){setPlaying(false);if(replay){current=Math.max(0,Math.min(replay.frames-1,current+direction));draw();}}
$('previous').onclick=()=>step(-1);$('next').onclick=()=>step(1);
$('fit').onclick=()=>{zoom=1;pan=[0,0];draw();};
document.querySelectorAll('[data-view]').forEach(button=>button.onclick=()=>{yaw=({front:0,side:Math.PI/2,back:Math.PI})[button.dataset.view];elevation=.08;draw();});
canvas.onpointerdown=event=>{drag={x:event.clientX,y:event.clientY,yaw,elevation,pan:[...pan],shift:event.shiftKey};canvas.setPointerCapture(event.pointerId);};
canvas.onpointermove=event=>{if(!drag)return;if(drag.shift){const factor=(framing?.radius||1)*2/canvas.clientHeight/zoom;pan=[drag.pan[0]-(event.clientX-drag.x)*factor,drag.pan[1]+(event.clientY-drag.y)*factor];}else{yaw=drag.yaw+(event.clientX-drag.x)*.008;elevation=Math.max(-1.4,Math.min(1.4,drag.elevation+(event.clientY-drag.y)*.006));}draw();};
canvas.onpointerup=canvas.onpointercancel=()=>drag=null;canvas.onwheel=event=>{event.preventDefault();zoom=Math.max(.25,Math.min(7,zoom*Math.exp(-event.deltaY*.001)));draw();};
new ResizeObserver(draw).observe(canvas);
$('import').onclick=()=>$('ruleFile').click();$('ruleFile').onchange=async()=>{const file=$('ruleFile').files[0];if(!file)return;try{if(file.size>256*1024)throw Error('规则文件超过 256 KiB');const text=await file.text();JSON.parse(text);$('rules').value=text;error();await rebuild();}catch(e){error('规则文件未载入：'+e.message);}};
$('loadAnimations').onclick=()=>$('animationsFile').click();$('animationsFile').onchange=async()=>{
  const files=Array.from($('animationsFile').files);if(!files.length)return;
  const importGeneration=++assetImportGeneration;
  try{const namespace=$('assetNamespace').value.trim();if(!/^[a-z0-9_.-]+$/.test(namespace)||namespace==='model')throw Error('请填写小写命名空间，例如 preview');
    const candidate={...externalAnimations};let size=0;
    for(const file of files){size+=file.size;if(size>128*1024)throw Error('本次载入的额外动作超过 128 KiB');const root=JSON.parse(await file.text());if(!root||typeof root!=='object'||!root.animations||typeof root.animations!=='object'||Array.isArray(root.animations))throw Error(file.name+' 缺少 animations 对象');for(const [name,clip] of Object.entries(root.animations)){if(!clip||typeof clip!=='object'||Array.isArray(clip))throw Error(file.name+' 的片段 '+name+' 必须是对象');candidate[namespace+':'+name]=clip;}}
    if(Object.keys(candidate).length>256||new TextEncoder().encode(JSON.stringify(candidate)).length>128*1024)throw Error('额外动作总量超出预览上限');
    if(importGeneration!==assetImportGeneration)return;
    if(await rebuildAnimations(candidate))$('assetStatus').textContent=`已载入 ${Object.keys(candidate).length} 个自定义片段`;
  }catch(e){if(importGeneration===assetImportGeneration)error('额外动作未载入，保留已有资源：'+e.message);}
  finally{if(importGeneration===assetImportGeneration)$('animationsFile').value='';}
};
$('export').onclick=()=>{try{const text=JSON.stringify(JSON.parse($('rules').value),null,2);const url=URL.createObjectURL(new Blob([text],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download='motion_rules.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),500);}catch(e){error(e.message);}};
$('emptyRules').onclick=()=>{$('rules').value=JSON.stringify(emptyRules,null,2);$('mode').value='rules';$('clipRow').hidden=true;$('controllerRow').hidden=true;rebuild();};
$('demoRules').onclick=()=>{$('rules').value=JSON.stringify({...emptyRules,extras:[
  {id:'demo-cape',models:['preview_demo'],source:{id:'model:cape_swing'},target:{bones:['Cape'],channels:['rotation']},blend:{mode:'additive'}},
  {id:'demo-tail',models:['preview_demo'],source:{id:'model:parallel_tail'},target:{bones:['Tail'],channels:['rotation']},playback:{clock:'authored'}}]},null,2);$('mode').value='rules';$('clipRow').hidden=true;$('controllerRow').hidden=true;rebuild();};
function tick(now){if(playing&&replay){if(!lastTime)lastTime=now;const elapsed=now-lastTime,interval=1000/replay.fps/+$('speed').value;if(elapsed>=interval){lastTime+=Math.floor(elapsed/interval)*interval;current+=Math.floor(elapsed/interval);if(current>=replay.frames){if($('loop').checked)current%=replay.frames;else{current=replay.frames-1;setPlaying(false);}}draw();}}requestAnimationFrame(tick);}
requestAnimationFrame(tick);
try{const initial=await api('initial');$('root').value=initial.root;await scan();}catch(e){error(e.message);$('busy').hidden=true;}
