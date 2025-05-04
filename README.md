# OpenCUI | structi


OpenCUI is a declarative and component-based framework for conversational user interface (CUI), designed to make it easy to build conversational frontend for your backend services.

Dialog Understanding:

The OpenCUI runtime defines a set of interface for dialog understanding, and provides reference implementation based on duckling and transformer based Tensorflow models that can be accessed from:
```
docker run -it --rm -p 8000:8000 registry.cn-beijing.aliyuncs.com/ni/apps:0616-v1
docker run -it --rm -p 8501:8501 registry.us-east-1.aliyuncs.com/framely/apps:du.private--20220815--tag--du-debug-v10.3-v2.2
./gradlew core:test
```
### Summary
OpenCUI is a declarative and component-based framework for building Conversational User Interfaces (CUI). 
Its main purpose and functionality include:
1. *Dialog Understanding*: The framework provides interfaces and implementations for natural language understanding, using technologies like Duckling and Tensorflow-based transformer models.
2. *Extensible Architecture*: The core design uses an extension system that allows plugging in different components (channels, providers, services) through a configuration-driven approach.
3. *Multi-channel Support*: The system can handle conversations across different messaging channels through the  IMessageChannel interface.
4. *Conversation Management*: It includes a sophisticated dialog management system with:
   - User session tracking
   - Dialog state management
   - Frame-based dialog modeling
   - Event handling
5. *AI Integration*: The codebase shows integration with AI models (like ChatGPT) through the System1 interface for generating responses.
6. *Live Agent Support*: The framework supports handoff to human agents when needed through the  **ISupport** interface.
7. *Knowledge Integration*: It can incorporate knowledge from various sources to enhance responses.

The architecture follows a modular approach where:
- **IChatbot** represents the core bot implementation
- **UserSession** manages conversation state
- **ExtensionManager** handles pluggable components
- **Dispatcher** routes messages between users and the system

The project is designed for building sophisticated conversational agents that can understand natural language, maintain context, and integrate with various backend services.
