import {installLandscapeMotion} from './landscape-motion.mjs';

const motion = installLandscapeMotion();
let sourcePhone = null;
let currentDemo = null;

const dialog = document.querySelector('#demo-dialog');
const title = document.querySelector('#dialog-title');
const body = document.querySelector('#dialog-body');
const demos = {
  run: ['记录详情', '<p class="preview-quote">这一周，坚持跑步三次。</p><p>目标 · 我 · 进行中</p><p>截止日期：2026.10.02</p><p>验证标准：本周完成三次跑步，保留运动记录。</p><p class="disclaimer">此处仅展示详情排版。原话、AI建议与用户确认在正式应用中分别保存。</p>'],
  read: ['记录详情', '<p class="preview-quote">读完《山居笔记》，写下一篇读后感。</p><p>承诺 · 阅读 · 进行中</p><p>截止日期：2026.10.05</p><p>验证标准：完成全书阅读并写下读后感。</p><p class="disclaimer">此处为固定演示记录，没有读取用户镖库。</p>'],
  create: ['记下原话', '<label for="quote-preview">原话</label><textarea id="quote-preview" placeholder="记下原话，留待时间验证。"></textarea><p class="disclaimer">可体验输入布局；本样板不会保存或上传内容。</p>'],
  home: ['首页', '<p>首页将山水主景、待跟进记录和新建操作放在同一条阅读路径。</p><p class="disclaimer">关闭此窗即可继续查看首页样板。</p>'],
  library: ['镖库', '<p>搜索、筛选与完整记录列表沿用既有功能。下一阶段以轻量页头延续青绿山水，正文保持清楚。</p><p class="disclaimer">本轮仅审核首页，其他页面仍使用已发布版本。</p>'],
  assistant: ['AI 助手', '<p>智能录入、对话、调查与周报沿用现有入口。</p><p class="disclaimer">百炼继续暂缓。本样板没有真实模型调用。</p>'],
  profile: ['我的', '<p>账号、同步、通知、备份和版本更新继续放在个人空间。</p><p class="disclaimer">本样板可在页头切换完整、减少和关闭动效；原生适配另行验收。</p>'],
  notifications: ['通知中心', '<p>正式应用在这里保留本机提醒，点击后进入对应记录。</p><p class="disclaimer">红点仅用于预览通知入口，不代表真实未读消息。</p>']
};

for (const button of document.querySelectorAll('[data-view-button]')) {
  button.addEventListener('click', () => {
    document.body.dataset.view = button.dataset.viewButton;
    for (const sibling of document.querySelectorAll('[data-view-button]')) {
      sibling.setAttribute('aria-pressed', String(sibling === button));
    }
    motion.refresh();
  });
}

for (const button of document.querySelectorAll('[data-demo]')) {
  button.addEventListener('click', () => {
    currentDemo = button.dataset.demo;
    sourcePhone = button.closest('.phone');
    dialog.returnValue = '';
    const demo = demos[currentDemo];
    dialog.querySelector('[value=done]').textContent = currentDemo === 'create' ? '完成预览' : '返回首页样板';
    title.textContent = demo[0];
    // Only fixed project-owned demo strings are used here; user input is never interpolated.
    body.innerHTML = demo[1];
    dialog.dataset.theme = button.closest('[data-theme]').dataset.theme;
    dialog.showModal();
    motion.refresh();
  });
}

dialog.addEventListener('close', () => {
  // Discard the unsubmitted demonstration draft when the preview closes.
  body.replaceChildren();
  motion.refresh();
  if (currentDemo === 'create' && dialog.returnValue === 'done') motion.feedback(sourcePhone);
  currentDemo = null; sourcePhone = null;
});
