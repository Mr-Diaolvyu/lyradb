import router from '@ohos.router';
import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import web_webview from '@ohos.web.webview';
import userAuth from '@ohos.userIAM.userAuth';
import picker from '@ohos.file.picker';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import promptAction from '@ohos.promptAction';
import { canonicalAppUrl, extractHttpsOrigin } from '../common/ServerUrlPolicy';

const PREFS_NAME = 'lyradb_mobile';
const KEY_SERVER_URL = 'server_url';
const KEY_BIOMETRIC = 'biometric_enabled';
const MAX_BASE64_CHARS = 48 * 1024 * 1024;

class DownloadBridge {
  private context?: common.UIAbilityContext;

  setContext(context: common.UIAbilityContext): void {
    this.context = context;
  }

  /**
   * 由同源 Vue 页面调用。保存操作必须经过系统文件选择器，由用户决定目标位置。
   */
  saveBase64(fileName: string, _mimeType: string, encoded: string): void {
    this.save(fileName, encoded);
  }

  private async save(fileName: string, encoded: string): Promise<void> {
    if (!this.context || encoded.length === 0 || encoded.length > MAX_BASE64_CHARS) {
      promptAction.showToast({ message: '导出失败：文件为空或超过大小限制' });
      return;
    }
    try {
      const safeName = this.sanitizeFileName(fileName);
      const bytes = new util.Base64Helper().decodeSync(encoded);
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [safeName];
      const documentPicker = new picker.DocumentViewPicker(this.context);
      const uris = await documentPicker.save(options);
      if (uris.length === 0) {
        return;
      }
      const file = fs.openSync(
        uris[0],
        fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC
      );
      try {
        fs.writeSync(file.fd, bytes.buffer, { length: bytes.byteLength });
      } finally {
        fs.closeSync(file);
      }
      promptAction.showToast({ message: '文件已保存' });
    } catch (error) {
      promptAction.showToast({ message: '保存已取消或失败' });
    }
  }

  private sanitizeFileName(value: string): string {
    const parts = value.split(/[\\/]/);
    const last = parts.length > 0 ? parts[parts.length - 1] : '';
    const cleaned = last.replace(/[<>:"|?*\x00-\x1F]/g, '_').substring(0, 120);
    return cleaned === '' || cleaned === '.' || cleaned === '..' ?
      'lyradb-export.bin' : cleaned;
  }
}

/**
 * ArkWeb 外壳主界面。仅加载配置服务端的同源 HTTPS 内容，
 * 禁止文件访问、混合内容与跨源导航。
 */
@Entry
@Component
struct Index {
  controller: web_webview.WebviewController = new web_webview.WebviewController();
  downloadBridge: DownloadBridge = new DownloadBridge();
  @State serverUrl: string = '';
  @State locked: boolean = false;
  @State lockMessage: string = '';

  aboutToAppear() {
    this.init();
  }

  async init() {
    const ctx = getContext(this) as common.UIAbilityContext;
    this.downloadBridge.setContext(ctx);
    const prefs = await preferences.getPreferences(ctx, PREFS_NAME);
    const storedUrl = await prefs.get(KEY_SERVER_URL, '') as string;
    const appUrl = canonicalAppUrl(storedUrl);
    if (appUrl === '') {
      router.replaceUrl({ url: 'pages/ServerConfig' });
      return;
    }
    if (appUrl !== storedUrl) {
      await prefs.put(KEY_SERVER_URL, appUrl);
      await prefs.flush();
    }
    this.serverUrl = appUrl;

    const biometric = await prefs.get(KEY_BIOMETRIC, false) as boolean;
    if (biometric) {
      this.locked = true;
      this.tryUnlock();
    }
  }

  /** 应用锁失败关闭：能力异常或认证失败时均保持锁定。 */
  tryUnlock() {
    this.locked = true;
    this.lockMessage = '';
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
    } catch (error) {
      this.lockMessage = '设备认证不可用，LyraDB 保持锁定';
    }
  }

  isTrustedUrl(value: string): boolean {
    const trusted = extractHttpsOrigin(this.serverUrl);
    return trusted !== '' && extractHttpsOrigin(value) === trusted;
  }

  build() {
    Column() {
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
          .fileAccess(false)
          .mixedMode(MixedMode.None)
          .javaScriptProxy({
            object: this.downloadBridge,
            name: 'LyraDBHarmony',
            methodList: ['saveBase64'],
            controller: this.controller
          })
          .onLoadIntercept((event) => {
            if (!event) {
              return true;
            }
            return !this.isTrustedUrl(event.data.getRequestUrl());
          })
          .onOverrideUrlLoading((request: WebResourceRequest) => {
            return !this.isTrustedUrl(request.getRequestUrl());
          })
          .width('100%')
          .layoutWeight(1)
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

  /** 返回键：优先回退 Web 历史，否则退出。 */
  onBackPress(): boolean {
    try {
      if (this.controller.accessBackward()) {
        this.controller.backward();
        return true;
      }
    } catch (error) {
      // controller 尚未就绪。
    }
    return false;
  }
}
