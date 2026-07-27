import router from '@ohos.router';
import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import web_webview from '@ohos.web.webview';
import userAuth from '@ohos.userIAM.userAuth';

const PREFS_NAME = 'lyradb_mobile';
const KEY_SERVER_URL = 'server_url';
const KEY_BIOMETRIC = 'biometric_enabled';

/**
 * ArkWeb 外壳主界面。
 *
 * 移动端为 BS 封装客户端：本页面承载顶部导航（切换服务端入口）与一个 Web（ArkWeb）组件，
 * 加载用户配置的远端 BS 服务端地址，复用其 Vue 前端完成登录、数据源管理、SQL 查询、结果导出等全部业务。
 * 本地不承载任何数据库驱动与连接。返回键优先回退 Web 历史。
 * 可选生物识别解锁（@ohos.userIAM.userAuth，在服务端配置页开关）。
 */
@Entry
@Component
struct Index {
  controller: web_webview.WebviewController = new web_webview.WebviewController();
  @State serverUrl: string = '';
  @State loaded: boolean = false;
  @State locked: boolean = false;
  @State lockMessage: string = '';

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
    const biometric = await prefs.get(KEY_BIOMETRIC, false) as boolean;
    if (biometric) {
      this.locked = true;
      this.serverUrl = url;
      this.tryUnlock();
      return;
    }
    this.serverUrl = url;
  }

  /** 生物识别解锁；设备不支持/未录入时直接放行 */
  tryUnlock() {
    try {
      const challenge = new Uint8Array(16);
      const authParam: userAuth.AuthParam = {
        challenge: challenge,
        authType: [userAuth.UserAuthType.FINGERPRINT, userAuth.UserAuthType.FACE, userAuth.UserAuthType.PIN],
        authTrustLevel: userAuth.AuthTrustLevel.ATL1
      };
      const widgetParam: userAuth.WidgetParam = { title: '解锁 LyraDB' };
      const instance = userAuth.getUserAuthInstance(authParam, widgetParam);
      instance.on('result', {
        onResult: (result: userAuth.UserAuthResult) => {
          if (result.result === userAuth.UserAuthResultCode.SUCCESS) {
            this.locked = false;
            this.lockMessage = '';
          } else {
            this.lockMessage = '解锁失败，请重试';
          }
        }
      });
      instance.start();
    } catch (e) {
      // 设备不支持或未录入生物特征：跳过解锁
      this.locked = false;
    }
  }

  build() {
    Column() {
      // 顶部导航：切换服务端入口
      Row() {
        Text('LyraDB')
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
        Blank()
        Button('切换服务端', { type: ButtonType.Capsule })
          .fontSize(12)
          .height(28)
          .backgroundColor('#F0F0F0')
          .fontColor('#333333')
          .onClick(() => {
            router.replaceUrl({ url: 'pages/ServerConfig' });
          })
      }
      .width('100%')
      .height(40)
      .padding({ left: 12, right: 12 })

      if (this.locked) {
        Column({ space: 16 }) {
          Text('🔒 LyraDB 已锁定')
            .fontSize(18)
            .fontWeight(FontWeight.Medium)
          Text(this.lockMessage)
            .fontSize(13)
            .fontColor('#888888')
          Button('解锁')
            .onClick(() => this.tryUnlock())
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.serverUrl !== '') {
        Web({ src: this.serverUrl, controller: this.controller })
          .javaScriptAccess(true)
          .domStorageAccess(true)
          .fileAccess(true)
          .mixedMode(MixedMode.Compatible)
          .width('100%')
          .layoutWeight(1)
          .onPageEnd(() => {
            this.loaded = true;
          })
      } else {
        Text('加载中…')
          .fontSize(16)
          .fontColor('#888888')
          .layoutWeight(1)
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
}
