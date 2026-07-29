import router from '@ohos.router';
import preferences from '@ohos.data.preferences';
import http from '@ohos.net.http';
import common from '@ohos.app.ability.common';
import userAuth from '@ohos.userIAM.userAuth';
import { appInfoUrl, canonicalAppUrl } from '../common/ServerUrlPolicy';

const PREFS_NAME = 'lyradb_mobile';
const KEY_SERVER_URL = 'server_url';
const KEY_BIOMETRIC = 'biometric_enabled';

/** 发布版服务端配置页：仅接受不含账号密码的 HTTPS origin 或 origin/api。 */
@Entry
@Component
struct ServerConfig {
  @State serverUrl: string = '';
  @State status: string = '';
  @State testing: boolean = false;
  @State biometricEnabled: boolean = false;

  aboutToAppear() {
    this.loadSaved();
  }

  async loadSaved() {
    const ctx = getContext(this) as common.UIAbilityContext;
    const prefs = await preferences.getPreferences(ctx, PREFS_NAME);
    const saved = await prefs.get(KEY_SERVER_URL, '') as string;
    if (saved) {
      const appUrl = canonicalAppUrl(saved);
      this.serverUrl = appUrl === '' ? saved : appUrl;
    }
    this.biometricEnabled = await prefs.get(KEY_BIOMETRIC, false) as boolean;
  }

  async saveBiometric(enabled: boolean) {
    const ctx = getContext(this) as common.UIAbilityContext;
    const prefs = await preferences.getPreferences(ctx, PREFS_NAME);
    await prefs.put(KEY_BIOMETRIC, enabled);
    await prefs.flush();
  }

  updateBiometric(enabled: boolean) {
    if (!enabled) {
      this.biometricEnabled = false;
      this.saveBiometric(false);
      return;
    }
    try {
      const authParam: userAuth.AuthParam = {
        challenge: new Uint8Array(16),
        authType: [
          userAuth.UserAuthType.FINGERPRINT,
          userAuth.UserAuthType.FACE,
          userAuth.UserAuthType.PIN
        ],
        authTrustLevel: userAuth.AuthTrustLevel.ATL1
      };
      const widgetParam: userAuth.WidgetParam = { title: '验证并启用 LyraDB 应用锁' };
      const instance = userAuth.getUserAuthInstance(authParam, widgetParam);
      instance.on('result', {
        onResult: (result: userAuth.UserAuthResult) => {
          const success = result.result === userAuth.UserAuthResultCode.SUCCESS;
          this.biometricEnabled = success;
          this.status = success ? '应用锁已启用' : '身份验证未通过，应用锁未启用';
          if (success) {
            this.saveBiometric(true);
          }
        }
      });
      instance.start();
    } catch (error) {
      this.biometricEnabled = false;
      this.status = '设备认证不可用，应用锁未启用';
    }
  }

  build() {
    Scroll() {
      Column({ space: 16 }) {
        Text('LyraDB')
          .fontSize(28)
          .fontWeight(FontWeight.Bold)
          .margin({ top: 40 })
        Text('连接到你的 BS 服务端')
          .fontSize(14)
          .fontColor('#888888')
        TextInput({
          placeholder: 'HTTPS origin 或 /api，如 https://db.example.com',
          text: this.serverUrl
        })
          .type(InputType.Normal)
          .width('100%')
          .margin({ top: 24 })
          .onChange((value: string) => {
            this.serverUrl = value;
          })
        Text(this.status)
          .fontSize(14)
          .fontColor('#888888')
          .width('100%')
        Button(this.testing ? '连接中…' : '测试连接')
          .width('100%')
          .margin({ top: 16 })
          .enabled(!this.testing)
          .onClick(() => this.testConnection())
        Button('进入')
          .width('100%')
          .onClick(() => this.enter())
        Row() {
          Text('启动时设备认证解锁')
            .fontSize(14)
          Blank()
          Toggle({ type: ToggleType.Switch, isOn: this.biometricEnabled })
            .onChange((isOn: boolean) => {
              this.updateBiometric(isOn);
            })
        }
        .width('100%')
        .margin({ top: 8 })
        Text('发布版仅连接 HTTPS 服务端。数据库凭据与会话不写入本地 Preferences；本地仅保存规范化的根页面地址与应用锁开关。')
          .fontSize(12)
          .fontColor('#888888')
          .margin({ top: 24 })
      }
      .padding(24)
      .width('100%')
    }
    .width('100%')
    .height('100%')
  }

  async enter() {
    const appUrl = canonicalAppUrl(this.serverUrl);
    if (appUrl === '') {
      this.status = '请输入不含账号密码的 HTTPS origin 或 origin/api';
      return;
    }
    const ctx = getContext(this) as common.UIAbilityContext;
    const prefs = await preferences.getPreferences(ctx, PREFS_NAME);
    await prefs.put(KEY_SERVER_URL, appUrl);
    await prefs.flush();
    this.serverUrl = appUrl;
    router.replaceUrl({ url: 'pages/Index' });
  }

  async testConnection() {
    const appUrl = canonicalAppUrl(this.serverUrl);
    if (appUrl === '') {
      this.status = '请输入不含账号密码的 HTTPS origin 或 origin/api';
      return;
    }
    this.serverUrl = appUrl;
    this.testing = true;
    this.status = '连接中…';
    this.status = await this.checkServer(appUrl);
    this.testing = false;
  }

  checkServer(appUrl: string): Promise<string> {
    return new Promise<string>((resolve) => {
      const request = http.createHttp();
      request.request(
        appInfoUrl(appUrl),
        {
          method: http.RequestMethod.GET,
          connectTimeout: 8000,
          readTimeout: 8000
        },
        (error, data) => {
          if (error) {
            resolve('无法连接服务端');
            request.destroy();
            return;
          }
          if (data.responseCode === 200) {
            try {
              const json = JSON.parse(data.result as string) as Record<string, Object>;
              const edition = json['edition'] as string;
              const auth = json['authRequired'] as boolean;
              const version = json['version'] as string;
              resolve(
                '连接成功：' + edition + ' 版（v' + version + '），' +
                (auth ? '需登录' : '免登录')
              );
            } catch (parseError) {
              resolve('连接成功，但响应解析失败');
            }
          } else {
            resolve('连接失败：HTTP ' + data.responseCode);
          }
          request.destroy();
        }
      );
    });
  }
}
