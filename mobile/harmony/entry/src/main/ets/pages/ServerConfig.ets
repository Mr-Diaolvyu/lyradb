import router from '@ohos.router';
import preferences from '@ohos.data.preferences';
import http from '@ohos.net.http';
import common from '@ohos.app.ability.common';

const PREFS_NAME = 'lyradb_mobile';
const KEY_SERVER_URL = 'server_url';

/**
 * 服务端地址配置页（ArkUI）。
 *
 * 首次启动或切换服务端时进入。填写 BS 服务端地址（个人自托管或企业），
 * 校验 /api/app/info 可达后持久化，随后进入 ArkWeb 主界面加载该地址。
 */
@Entry
@Component
struct ServerConfig {
  @State serverUrl: string = '';
  @State status: string = '';
  @State testing: boolean = false;

  aboutToAppear() {
    this.loadSaved();
  }

  async loadSaved() {
    const ctx = getContext(this) as common.UIAbilityContext;
    const prefs = await preferences.getPreferences(ctx, PREFS_NAME);
    const saved = await prefs.get(KEY_SERVER_URL, '') as string;
    if (saved) {
      this.serverUrl = saved;
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
        TextInput({ placeholder: '服务端地址，如 http://192.168.1.10:8080', text: this.serverUrl })
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
        Text('说明：手机端为 BS 封装客户端，所有数据库连接与查询均由所连服务端完成。可连接个人自托管服务或企业 BS 服务。')
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

  normalize(raw: string): string {
    let s = raw.trim();
    if (s === '') {
      return '';
    }
    if (!s.startsWith('http://') && !s.startsWith('https://')) {
      s = 'http://' + s;
    }
    while (s.endsWith('/')) {
      s = s.substring(0, s.length - 1);
    }
    return s;
  }

  async enter() {
    const url = this.normalize(this.serverUrl);
    if (url === '') {
      this.status = '请先填写服务端地址';
      return;
    }
    const ctx = getContext(this) as common.UIAbilityContext;
    const prefs = await preferences.getPreferences(ctx, PREFS_NAME);
    await prefs.put(KEY_SERVER_URL, url);
    await prefs.flush();
    router.replaceUrl({ url: 'pages/Index' });
  }

  async testConnection() {
    const url = this.normalize(this.serverUrl);
    if (url === '') {
      this.status = '请先填写服务端地址';
      return;
    }
    this.testing = true;
    this.status = '连接中…';
    const result = await this.checkServer(url);
    this.status = result;
    this.testing = false;
  }

  checkServer(base: string): Promise<string> {
    return new Promise<string>((resolve) => {
      const req = http.createHttp();
      req.request(base + '/api/app/info', { method: http.RequestMethod.GET }, (err, data) => {
        if (err) {
          resolve('无法连接：' + JSON.stringify(err));
          req.destroy();
          return;
        }
        if (data.responseCode === 200) {
          try {
            const json = JSON.parse(data.result as string) as Record<string, Object>;
            const edition = json['edition'] as string;
            const auth = json['authRequired'] as boolean;
            const version = json['version'] as string;
            resolve('连接成功：' + edition + ' 版（v' + version + '），' + (auth ? '需登录' : '免登录'));
          } catch (e) {
            resolve('连接成功，但响应解析失败');
          }
        } else {
          resolve('连接失败：HTTP ' + data.responseCode);
        }
        req.destroy();
      });
    });
  }
}
