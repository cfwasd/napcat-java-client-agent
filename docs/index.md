---
layout: home

hero:
  name: NapCat Java SDK
  text: 现代化的 Java Bot 开发框架
  tagline: 基于 NapCat OneBot11 协议，集成 AI Agent 能力，支持注解驱动与接口驱动两种编程模型
  image:
    src: /logo.svg
    alt: NapCat Java SDK
  actions:
    - theme: brand
      text: 快速开始
      link: /01-quick-start
    - theme: alt
      text: GitHub
      link: https://github.com/cfwasd/napcat-java-agent

features:
  - icon: 🚀
    title: 开箱即用
    details: Spring Boot Starter 自动配置，引入依赖即可开始编写 Bot，支持 JDK 17+。
  - icon: 📝
    title: 双编程模型
    details: 注解式（@OnGroupMessage、@Command）与接口式（EventHandler、CommandHandler）并存，灵活适应不同编码风格。
  - icon: 🤖
    title: AI Agent
    details: 内置 ReAct 轻量循环，支持 Function Calling / Tool Use，多 LLM 后端无缝切换，含多模态图片理解、持久化长期记忆与定时任务调度。
  - icon: 🌐
    title: 全协议通信
    details: 支持 HTTP Server / Client、WebSocket Server / Client 四种 NapCat 通信方式，可混合使用。
  - icon: 🔧
    title: 高度可扩展
    details: 组合注解、自定义适配器、插件化设计，满足复杂业务场景需求。
  - icon: ⚡
    title: 轻量高效
    details: 事件驱动架构，高性能路由，内存占用低，轻松支撑高并发消息处理。
---
