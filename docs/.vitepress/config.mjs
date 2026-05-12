import { defineConfig } from 'vitepress'

// https://vitepress.dev/reference/site-config
export default defineConfig({
  title: 'NapCat Java SDK',
  description: '基于 NapCat OneBot11 协议的 Java Bot 开发框架，集成 AI Agent 能力',
  lang: 'zh-CN',
  base: '/napcat-java-agent/',

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
  ],

  themeConfig: {
    // https://vitepress.dev/reference/default-theme-config
    logo: '/logo.svg',

    nav: [
      { text: '快速开始', link: '/01-quick-start' },
      { text: '指南', link: '/02-programming-model' },
      { text: '关于', link: '/README' },
    ],

    sidebar: [
      {
        text: '指南',
        items: [
          { text: '快速开始', link: '/01-quick-start' },
          { text: '编程模型', link: '/02-programming-model' },
          { text: '配置参考', link: '/03-configuration-reference' },
          { text: '事件与消息', link: '/04-event-message-model' },
          { text: '通信适配器', link: '/05-adapter-guide' },
          { text: 'Agent 指南', link: '/06-agent-guide' },
          { text: '内部架构', link: '/07-internal-architecture' },
        ],
      },
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/cfwasd/napcat-java-agent' },
    ],

    footer: {
      message: '基于 MIT 许可发布',
      copyright: 'Copyright © cfwasd',
    },

    search: {
      provider: 'local',
      options: {
        locales: {
          zh: {
            translations: {
              button: {
                buttonText: '搜索文档',
                buttonAriaLabel: '搜索文档',
              },
              modal: {
                noResultsText: '未找到相关结果',
                resetButtonTitle: '清除搜索',
                footer: {
                  selectText: '选择',
                  navigateText: '切换',
                  closeText: '关闭',
                },
              },
            },
          },
        },
      },
    },

    editLink: {
      pattern: 'https://github.com/cfwasd/napcat-java-agent/edit/main/docs/:path',
      text: '在 GitHub 上编辑此页',
    },

    docFooter: {
      prev: '上一页',
      next: '下一页',
    },

    outline: {
      label: '页面导航',
    },

    lastUpdated: {
      text: '最后更新于',
      formatOptions: {
        dateStyle: 'short',
        timeStyle: 'medium',
      },
    },

    langMenuLabel: '多语言',
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '菜单',
    darkModeSwitchLabel: '主题',
    lightModeSwitchTitle: '切换到浅色模式',
    darkModeSwitchTitle: '切换到深色模式',
  },
})
