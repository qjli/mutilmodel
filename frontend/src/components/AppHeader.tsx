import { Badge, Typography } from "antd";
import "./AppHeader.css";

export type AppHeaderProps = {
  sessionId: string;
};

function shortSession(id: string) {
  if (id.length <= 12) {
    return id;
  }
  return `${id.slice(0, 8)}…${id.slice(-4)}`;
}

/**
 * 全站顶栏：品牌、定位文案、环境与会话元信息，与下方对话舱/表单区视觉统一。
 */
export function AppHeader({ sessionId }: AppHeaderProps) {
  return (
    <header className="app-header" role="banner">
      <div className="app-header__accent" aria-hidden />
      <div className="app-header__inner">
        <div className="app-header__brand">
          <div className="app-header__mark" aria-hidden>
            <span className="app-header__mark-icon" />
          </div>
          <div className="app-header__titles">
            <Typography.Title level={3} className="app-header__title">
              多模态智能填报助手
            </Typography.Title>
            <p className="app-header__subtitle">
              对话理解意图 · 结构化回填企业信息 · 支持附件上传与表单校验
            </p>
          </div>
        </div>
        <nav className="app-header__nav" aria-label="快捷导航">
          <a className="app-header__nav-link" href="#main-content">
            首页
          </a>
          <a className="app-header__nav-link" href="#main-content">
            账户设置
          </a>
          <a className="app-header__nav-link" href="#main-content">
            <Badge count={1} size="small" offset={[6, -2]}>
              <span>消息</span>
            </Badge>
          </a>
        </nav>
        <div className="app-header__aside">
          <span className="app-header__chip">演示环境</span>
          <Typography.Text
            className="app-header__chip app-header__chip--session"
            copyable={{ text: sessionId, tooltips: ["复制会话 ID", "已复制"] }}
          >
            会话 {shortSession(sessionId)}
          </Typography.Text>
        </div>
      </div>
    </header>
  );
}
