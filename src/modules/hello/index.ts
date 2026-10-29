import type { UtilityModule } from '../../core/types';

export const hello: UtilityModule = {
  id: 'hello',
  title: 'Hello World',
  description: 'A first module to check that everything works.',
  icon: '👋',
  mount(container) {
    const heading = document.createElement('p');
    heading.className = 'hello-greeting';
    heading.textContent = 'Hello, World!';

    const counter = document.createElement('button');
    counter.type = 'button';
    counter.className = 'button';
    let taps = 0;
    const update = () => (counter.textContent = `Tapped ${taps} time${taps === 1 ? '' : 's'}`);
    counter.addEventListener('click', () => {
      taps++;
      update();
    });
    update();

    container.append(heading, counter);
  },
};
