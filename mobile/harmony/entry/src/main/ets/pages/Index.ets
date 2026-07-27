import router from '@ohos.router';
import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import web_webview from '@ohos.web.webview';

const PREFS_NAME = 'lyradb_mobile';
const KEY_SERVER_URL = 'server_url';

/**
 * ArkWeb 外壳主界面。
 *
 * 移动端为 BS 封装客户端：本页面仅承载一个 Web（ArkWeb）组件，加载用户配置的
 * 远端 BS 服务端地址，复用其 Vue 前端完成登录、数据源管理、SQL 查询、结果导出等全部业务。
 * 本地不承载任何数据库驱动与连接。返回键优先回退 Web 历史。
 */
@Entry
@Component
struct Index {
  controller: web_webview.WebviewController = new web_webview.WebviewController();
  @State serverUrl: string = '';
  @State loaded: boolean = false;

  aboutToAppear() {
    this.init();
  }

  async init() {
    const ctx = getContext(this) as common.UIAbilityContext;
    const prefs = await preferences.getPreferences(ctx, PREFS_NAME);
    const url = await prefs.get(KEY_SERVER_URL, '') as string;
    if (!url) {
      router.replaceUrl({ url: 'pages/ServerConfig' });
      return;
    }
    this.serverUrl = url;
  }

  build() {
    Column() {
      if (this.serverUrl !== '') {
        Web({ src: this.serverUrl, controller: this.controller })
          .javaScriptAccess(true)
          .domStorageAccess(true)
          .fileAccess(true)
          .mixedMode(MixedMode.Compatible)
          .width('100%')
          .height('100%')
          .onPageEnd(() => {
            this.loaded = true;
          })
      } else {
        Text('加载中…')
          .fontSize(16)
          .fontColor('#888888')
      }
    }
    .width('100%')
    .height('100%')
  }

  /** 返回键：优先回退 Web 历史，否则退出 */
  onBackPress(): boolean {
    try {
      if (this.controller.accessBackward()) {
        this.controller.backward();
        return true;
      }
    } catch (e) {
      // controller 尚未就绪
    }
    return false;
  }

  /** 顶部导航：切换服务端 */
  onPageShow() {
  }
}
