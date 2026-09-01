/* ============================================================
   Steam Mchat 移动化注入脚本 v7-lite（2026-09-01）
   最小剂量：只隐藏 Steam 顶部横栏，别的什么都不做。

   安全红线（v4~v6 灰屏事故定案）：
   - 只改已有元素的 inline style（属性改写，React 不在乎）
   - 绝不向任何容器增删子节点（React 节点核对撞见外来
     节点 → NotFoundError → SPA 整体卸载 → 灰屏）
   - 不点页面自己的控件（程序点击异常无法兜底）
   本脚本严格遵守：全文无一处 createElement/appendChild。
   ============================================================ */
(function () {
    if (window.__mchatPatch) return;
    window.__mchatPatch = true;

    /* 隐藏顶部横栏（CSS-module 哈希类，前缀匹配防哈希漂移） */
    function hideHeader() {
        var h = document.querySelector('[class*="main_SteamPageHeader"]');
        if (h && h.style.display !== 'none') h.style.display = 'none';
    }

    /* SPA 异步启动，元素可能迟到：立即 + 延时补两刀 */
    hideHeader();
    setTimeout(hideHeader, 800);
    setTimeout(hideHeader, 2000);

    /* React 重绘可能把 inline style 冲掉：观察 DOM 变动，节流重补 */
    try {
        var timer = null;
        new MutationObserver(function () {
            if (timer) return;
            timer = setTimeout(function () {
                timer = null;
                hideHeader();
            }, 300);
        }).observe(document.body, { childList: true, subtree: true });
    } catch (e) { /* 尽力而为 */ }
})();
